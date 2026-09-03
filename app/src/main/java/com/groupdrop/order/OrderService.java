package com.groupdrop.order;

import com.groupdrop.campaign.CampaignAvailabilityNotifier;
import com.groupdrop.common.ApiException;
import com.groupdrop.common.GroupdropProperties;
import com.groupdrop.user.User;
import com.groupdrop.user.UserRepository;
import com.groupdrop.user.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OrderService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final CampaignAvailabilityNotifier availabilityNotifier;
    private final InventoryMetrics metrics;
    private final GroupdropProperties properties;
    private final Clock clock;

    public OrderService(UserRepository userRepository, OrderRepository orderRepository,
                        CampaignAvailabilityNotifier availabilityNotifier, InventoryMetrics metrics,
                        GroupdropProperties properties, Clock clock) {
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.availabilityNotifier = availabilityNotifier;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public OrderResponse createOrder(String requesterEmail, Long campaignId, String idempotencyKey,
                                     CreateOrderRequest request) {
        User buyer = requireBuyer(requesterEmail);
        List<CreateOrderRequest.Item> items = validateItems(request);
        String key = validateIdempotencyKey(idempotencyKey);
        String requestHash = hash(campaignId, items);
        String scope = "ORDER:" + buyer.getId();
        Instant now = Instant.now(clock);

        if (!orderRepository.claimIdempotency(scope, key, requestHash, now, now.plus(properties.idempotencyKeyTtl()))) {
            OrderRepository.IdempotencyRecord existing = orderRepository.findIdempotency(scope, key)
                    .orElseThrow(() -> new IllegalStateException("멱등 요청 레코드를 찾을 수 없습니다."));
            if (!existing.requestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "같은 Idempotency-Key를 다른 주문 요청에 재사용할 수 없습니다.");
            }
            if (existing.resourceId() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                        "같은 주문 요청이 처리 중입니다.");
            }
            return toResponse(requireOwnedOrder(existing.resourceId(), buyer.getId()));
        }

        OrderRepository.CampaignGate campaign = orderRepository.lockOrderableCampaign(campaignId, now)
                .orElseThrow(() -> notOrderableCampaign(campaignId, now));

        int totalQuantity = Math.toIntExact(items.stream()
                .mapToLong(item -> item.quantity().longValue())
                .sum());
        long totalAmount;
        try {
            totalAmount = Math.multiplyExact(campaign.dealPrice(), totalQuantity);
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORDER_AMOUNT_TOO_LARGE", "주문 금액 범위를 초과했습니다.");
        }

        orderRepository.ensurePurchaseCounter(campaignId, buyer.getId(), now);
        if (!orderRepository.incrementPurchaseCounter(
                campaignId, buyer.getId(), totalQuantity, campaign.purchaseLimit(), now)) {
            throw new ApiException(HttpStatus.CONFLICT, "PURCHASE_LIMIT_EXCEEDED", "캠페인별 구매 수량 제한을 초과했습니다.");
        }

        Instant expiresAt = now.plus(properties.reservationDuration());
        Long orderId = orderRepository.insertOrder(campaignId, buyer.getId(), campaign.policyVersionId(),
                totalAmount, totalQuantity, expiresAt, now);

        for (CreateOrderRequest.Item item : items) {
            OrderRepository.ReservedInventory inventory = metrics.timeInventoryUpdate(() ->
                    orderRepository.reserveInventory(campaignId, item.productSkuId(), item.quantity()).orElse(null));
            if (inventory == null) {
                if (!orderRepository.campaignContainsSku(campaignId, item.productSkuId())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "ORDER_SKU_NOT_IN_CAMPAIGN",
                            "캠페인에 포함되지 않은 SKU입니다: " + item.productSkuId());
                }
                metrics.recordSoldOut();
                throw new ApiException(HttpStatus.CONFLICT, "INVENTORY_SOLD_OUT", "요청한 SKU의 재고가 부족합니다.");
            }
            long lineAmount = Math.multiplyExact(campaign.dealPrice(), item.quantity());
            Long orderItemId = orderRepository.insertOrderItem(orderId, inventory.campaignSkuId(),
                    item.quantity(), campaign.dealPrice(), lineAmount);
            orderRepository.insertReservation(orderItemId, inventory.inventoryId(), item.quantity(), expiresAt, now);
        }

        orderRepository.completeIdempotency(scope, key, orderId, now);
        afterCommit(campaignId, true);
        return toResponse(orderRepository.findOrder(orderId).orElseThrow());
    }

    /**
     * REF-01 주문 취소. 결제 완료 <b>전</b> 주문만 취소할 수 있고, 결제가 붙어 있더라도
     * {@code READY}·{@code FAILED}일 때만 허용한다. {@code PROCESSING}·{@code UNKNOWN} 결제가 있으면
     * 거부하고 확정을 기다리게 한다 — 성공 결제가 붙은 {@code CANCELLED} 주문을 만들지 않기 위해서다.
     *
     * <p>이미 취소·만료된 주문에 대한 반복 호출은 같은 결과를 돌려준다 (멱등).
     */
    @Transactional
    public OrderResponse cancelOrder(String requesterEmail, Long orderId) {
        User buyer = requireBuyer(requesterEmail);
        OrderRepository.OrderSnapshot order = requireOwnedOrder(orderId, buyer.getId());
        String status = order.header().status();
        if ("CANCELLED".equals(status) || "EXPIRED".equals(status)) {
            return toResponse(order);
        }
        if (!"PENDING_PAYMENT".equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE",
                    "결제 완료 전 주문만 취소할 수 있습니다. 현재 상태: " + status);
        }
        if (orderRepository.hasUnsettledPayment(orderId)) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_SETTLED",
                    "확정되지 않은 결제가 있어 취소할 수 없습니다. 결제 확정 후 다시 시도하세요.");
        }

        Instant now = Instant.now(clock);
        if (!orderRepository.cancelOrder(orderId, now)) {
            // 위 검사와 이 UPDATE 사이에 결제가 들어왔다. 판정 원천은 조건부 UPDATE 쪽이다.
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_SETTLED",
                    "취소 처리 중 결제가 진행되어 취소하지 못했습니다. 결제 확정 후 다시 시도하세요.");
        }
        OrderRepository.OrderHeader header = order.header();
        if (!orderRepository.decrementPurchaseCounter(
                header.campaignId(), header.buyerId(), header.totalQuantity(), now)) {
            throw new IllegalStateException("구매 카운터 복구 불변식 위반: orderId=" + orderId);
        }
        for (OrderRepository.ExpiredReservation reservation : orderRepository.releaseReservations(orderId, now)) {
            if (!orderRepository.restoreInventory(reservation.inventoryId(), reservation.quantity())) {
                throw new IllegalStateException("취소 재고 복구 불변식 위반: inventoryId=" + reservation.inventoryId());
            }
        }
        afterCommit(header.campaignId(), false);
        return toResponse(orderRepository.findOrder(orderId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrders(String requesterEmail) {
        User buyer = requireBuyer(requesterEmail);
        return orderRepository.findOrdersByBuyer(buyer.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String requesterEmail, Long orderId) {
        User buyer = requireBuyer(requesterEmail);
        return toResponse(requireOwnedOrder(orderId, buyer.getId()));
    }

    private List<CreateOrderRequest.Item> validateItems(CreateOrderRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORDER_ITEMS_REQUIRED", "주문 항목은 1개 이상이어야 합니다.");
        }
        Set<Long> skuIds = new HashSet<>();
        int total = 0;
        for (CreateOrderRequest.Item item : request.items()) {
            if (item == null || item.productSkuId() == null || item.quantity() == null || item.quantity() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORDER_ITEM_INVALID", "SKU ID와 1 이상의 수량이 필요합니다.");
            }
            if (!skuIds.add(item.productSkuId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORDER_DUPLICATE_SKU", "주문에 같은 SKU를 중복 지정할 수 없습니다.");
            }
            try {
                total = Math.addExact(total, item.quantity());
            } catch (ArithmeticException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORDER_QUANTITY_TOO_LARGE", "주문 수량 범위를 초과했습니다.");
            }
        }
        return request.items().stream()
                .sorted(Comparator.comparing(CreateOrderRequest.Item::productSkuId))
                .toList();
    }

    private String validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 헤더는 필수입니다.");
        }
        if (key.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_TOO_LONG", "Idempotency-Key는 200자 이하여야 합니다.");
        }
        return key;
    }

    private String hash(Long campaignId, List<CreateOrderRequest.Item> items) {
        String canonical = campaignId + "|" + items.stream()
                .sorted(Comparator.comparing(CreateOrderRequest.Item::productSkuId))
                .map(item -> item.productSkuId() + ":" + item.quantity())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private ApiException notOrderableCampaign(Long campaignId, Instant now) {
        OrderRepository.CampaignState state = orderRepository.findCampaignState(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND",
                        "캠페인을 찾을 수 없습니다: " + campaignId));
        if (now.isBefore(state.startsAt()) || !now.isBefore(state.endsAt())) {
            return new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_OUTSIDE_SALES_PERIOD", "캠페인 판매 기간이 아닙니다.");
        }
        return new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_NOT_ORDERABLE",
                "OPEN 또는 SOLD_OUT 상태의 캠페인만 주문할 수 있습니다. 현재 상태: " + state.status());
    }

    private User requireBuyer(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        if (user.getRole() != UserRole.BUYER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "구매자만 주문할 수 있습니다.");
        }
        return user;
    }

    private OrderRepository.OrderSnapshot requireOwnedOrder(Long orderId, Long buyerId) {
        OrderRepository.OrderSnapshot order = orderRepository.findOrder(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
        if (!order.header().buyerId().equals(buyerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_NOT_OWNER", "본인 주문만 조회할 수 있습니다.");
        }
        return order;
    }

    private void afterCommit(Long campaignId, boolean recordSuccess) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (recordSuccess) {
                    metrics.recordReservationSuccess();
                }
                availabilityNotifier.refreshAsync(campaignId);
            }
        });
    }

    private OrderResponse toResponse(OrderRepository.OrderSnapshot snapshot) {
        OrderRepository.OrderHeader header = snapshot.header();
        return new OrderResponse(header.id(), header.campaignId(), header.status(), header.totalAmount(),
                header.totalQuantity(), header.expiresAt(), snapshot.items().stream()
                .map(item -> new OrderResponse.Item(item.id(), item.productSkuId(), item.quantity(),
                        item.unitPrice(), item.lineAmount(), item.reservationStatus()))
                .toList());
    }
}
