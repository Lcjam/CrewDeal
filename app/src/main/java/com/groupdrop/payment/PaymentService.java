package com.groupdrop.payment;

import com.groupdrop.common.ApiException;
import com.groupdrop.common.CampaignTransactionBarrier;
import com.groupdrop.common.GroupdropProperties;
import com.groupdrop.common.IdempotencyRepository;
import com.groupdrop.common.Json;
import com.groupdrop.user.User;
import com.groupdrop.user.UserRepository;
import com.groupdrop.user.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PAY-01·02·03의 요청 스레드 경로.
 *
 * <p>트랜잭션을 애노테이션이 아니라 {@link TransactionTemplate}으로 여는 이유는 경계가 이 클래스의
 * 핵심 설계이기 때문이다 — 준비(트랜잭션) → PG 호출(트랜잭션 밖) → 확정(트랜잭션)의 3단이며,
 * 가운데 외부 호출이 DB 트랜잭션을 물고 있으면 안 된다 (ADR-003).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String SOURCE = "request";
    static final String RESOURCE_TYPE = "PAYMENT";
    private static final int DEFAULT_LIST_LIMIT = 100;

    private final UserRepository users;
    private final CampaignTransactionBarrier campaignBarrier;
    private final PaymentRepository payments;
    private final IdempotencyRepository idempotency;
    private final PaymentFinalizer finalizer;
    private final PgClient pgClient;
    private final PaymentMetrics metrics;
    private final GroupdropProperties properties;
    private final TransactionTemplate transactions;
    private final Json json;
    private final Clock clock;

    public PaymentService(UserRepository users, CampaignTransactionBarrier campaignBarrier,
                          PaymentRepository payments, IdempotencyRepository idempotency,
                          PaymentFinalizer finalizer, PgClient pgClient, PaymentMetrics metrics,
                          GroupdropProperties properties, TransactionTemplate transactions,
                          Json json, Clock clock) {
        this.users = users;
        this.campaignBarrier = campaignBarrier;
        this.payments = payments;
        this.idempotency = idempotency;
        this.finalizer = finalizer;
        this.pgClient = pgClient;
        this.metrics = metrics;
        this.properties = properties;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
    }

    public Outcome requestPayment(String requesterEmail, Long orderId, String idempotencyKey,
                                  CreatePaymentRequest request) {
        User buyer = requireBuyer(requesterEmail);
        String key = validateIdempotencyKey(idempotencyKey);
        String scope = "POST:/api/orders/" + orderId + "/payments";

        Preparation preparation = transactions.execute(status -> prepare(buyer, orderId, scope, key, request));
        if (preparation.replay() != null) {
            return preparation.replay();
        }

        // ── 트랜잭션 밖 ── PG 호출. 여기서 죽어도 PROCESSING 결제는 고아 스윕이 UNKNOWN으로 회수한다 (PAY-03).
        PgClient.ConfirmResult result = metrics.timePgConfirm(() -> pgClient.confirm(
                new PgClient.ConfirmCommand(preparation.merchantPaymentId(), orderId, preparation.amount())));

        return transactions.execute(status -> settle(scope, key, preparation, result));
    }

    private Preparation prepare(User buyer, Long orderId, String scope, String key, CreatePaymentRequest request) {
        Instant now = Instant.now(clock);
        CampaignTransactionBarrier.CampaignLock campaign = campaignBarrier.lockByOrderId(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
        if (!campaign.allowsExistingOrderPayment()) {
            throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_NOT_PAYABLE",
                    "강제 종료되었거나 결제를 허용하지 않는 캠페인입니다. 현재 상태: " + campaign.status());
        }
        PaymentRepository.OrderForPayment order = payments.findOrderForPayment(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
        if (!order.buyerId().equals(buyer.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_NOT_OWNER", "본인 주문만 결제할 수 있습니다.");
        }
        if (!"PENDING_PAYMENT".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_PAYABLE",
                    "결제 대기 상태의 주문만 결제할 수 있습니다. 현재 상태: " + order.status());
        }
        if (request == null || request.amount() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_AMOUNT_REQUIRED", "결제 금액은 필수입니다.");
        }
        if (request.amount() != order.totalAmount()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_AMOUNT_MISMATCH",
                    "결제 금액이 주문 금액과 일치하지 않습니다.");
        }

        String requestHash = hash(orderId, order.totalAmount());
        if (!idempotency.claim(scope, key, requestHash, now, now.plus(properties.idempotencyKeyTtl()))) {
            metrics.recordDuplicatePrevented();
            return new Preparation(replayOrReject(scope, key, requestHash, now), null, null, null, 0L);
        }

        // PAY-01 이중 결제 예방. 이 검사와 아래 INSERT 사이의 경쟁 창은 의도적으로 남아 있으며,
        // 뚫린 경우는 부분 유니크가 패자를 만들어 SUPERSEDED 보상으로 보낸다.
        if (payments.hasNonFinalPayment(orderId)) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_ALREADY_IN_PROGRESS",
                    "이 주문에 진행 중인 결제가 있습니다.");
        }

        Long paymentId = payments.insertReadyPayment(orderId, order.totalAmount(), now);
        // PAY-02: 선점과 결제를 같은 커밋에서 잇는다. PG 호출 중 이 스레드가 죽어도 고착 선점을
        // 결제의 현재 상태로 회수할 수 있게 하기 위함이다 (OrphanPaymentSweepService#reclaimStaleIdempotency).
        idempotency.attachResource(scope, key, RESOURCE_TYPE, paymentId, now);
        String merchantPaymentId = "mpay_" + paymentId;
        // PAY-01: 시도 기록과 READY → PROCESSING은 같은 트랜잭션이어야 한다.
        // 어긋나면 "attempt 있음 + READY" 고아가 스윕에도 대사에도 잡히지 않는다.
        Long attemptId = payments.insertAttempt(paymentId, merchantPaymentId, order.totalAmount(), now);
        if (!payments.markProcessing(paymentId, now)) {
            // 결제 행을 만든 뒤 PG로 넘어가기 전에 주문이 취소된 경쟁이다 (10.2는 결제 READY 동안의
            // 취소를 허용한다). 오류가 아니라 경쟁이므로 409로 돌려준다. 이 예외는 준비 트랜잭션을
            // 롤백해 방금 만든 payments·payment_attempts 행을 함께 되돌리는데, PG 호출 전이라
            // 외부에 남은 흔적이 없으므로 고아를 만들지 않는 쪽이 맞다 (PAY-01의 "attempt 있음 + READY"
            // 고아는 PG 호출 이후에만 문제가 된다).
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_PAYABLE",
                    "결제를 시작하기 전에 주문이 결제 불가 상태가 되었습니다.");
        }
        return new Preparation(null, paymentId, attemptId, merchantPaymentId, order.totalAmount());
    }

    private Outcome settle(String scope, String key, Preparation preparation, PgClient.ConfirmResult result) {
        Instant now = Instant.now(clock);
        PaymentRepository.PaymentSnapshot payment = payments.findPayment(preparation.paymentId())
                .orElseThrow(() -> new IllegalStateException("결제를 찾을 수 없습니다: " + preparation.paymentId()));

        switch (result.outcome()) {
            case SUCCEEDED -> {
                payments.finishAttempt(preparation.attemptId(), "SUCCEEDED", null, now);
                finalizer.succeed(payment, result.providerPaymentId(), result.approvedAt(), SOURCE);
            }
            case FAILED -> {
                payments.finishAttempt(preparation.attemptId(), "FAILED", result.detail(), now);
                finalizer.fail(payment, result.providerPaymentId(), result.failureCode(), result.detail(), SOURCE);
            }
            case TIMEOUT -> {
                // 타임아웃은 실패가 아니다. 웹훅·조회·대사가 확정할 때까지 UNKNOWN으로 둔다 (PAY-03).
                payments.finishAttempt(preparation.attemptId(), "TIMEOUT", result.detail(), now);
                if (payments.markUnknown(preparation.paymentId(), now)) {
                    metrics.recordUnknown();
                }
            }
        }

        PaymentRepository.PaymentSnapshot settled = payments.findPayment(preparation.paymentId()).orElseThrow();
        PaymentResponse body = PaymentResponse.from(settled);
        int httpStatus = PaymentResponse.httpStatusFor(settled.status());
        if (idempotency.completeIfInProgress(scope, key, RESOURCE_TYPE, settled.id(), httpStatus,
                json.write(body), now)) {
            return new Outcome(httpStatus, body);
        }
        return replayReclaimed(scope, key, settled.id());
    }

    /**
     * 이 요청이 고아 스윕 임계(PG 타임아웃 × 2)보다 오래 걸려, 스윕이 선점을 결제의 그때 상태로 먼저 완료한 경우다
     * (캠페인 락 대기가 길면 GC 정지 없이도 일어난다). 저장된 응답을 덮어쓰지 않고 그대로 돌려준다 — 같은 키로
     * 재요청한 클라이언트가 이미 그 응답을 받았을 수 있고, PAY-02는 같은 키에 한 가지 응답만 허용한다.
     * 결제 상태 전이와 Outbox는 이 트랜잭션에서 정상 커밋되므로 정합성 손실은 없다. 202를 받은 클라이언트는
     * 단건 폴링으로 확정을 본다.
     */
    private Outcome replayReclaimed(String scope, String key, Long paymentId) {
        IdempotencyRepository.Record record = idempotency.find(scope, key)
                .orElseThrow(() -> new IllegalStateException("멱등 요청 레코드를 찾을 수 없습니다: " + scope + "/" + key));
        if (!record.completed() || !RESOURCE_TYPE.equals(record.resourceType())
                || !paymentId.equals(record.resourceId())) {
            throw new IllegalStateException("멱등 요청 완료 기록이 유실되었습니다: " + scope + "/" + key);
        }
        metrics.recordLateSettleReplayed();
        log.warn("결제 {}의 요청 확정이 고착 선점 회수보다 늦었습니다. 저장된 {} 응답을 재생합니다.",
                paymentId, record.responseStatus());
        return new Outcome(record.responseStatus(), json.read(record.responseBody(), PaymentResponse.class));
    }

    /** PAY-02: 같은 키 재요청의 세 갈래 — 만료·해시 불일치·처리 중·완료 재생. */
    private Outcome replayOrReject(String scope, String key, String requestHash, Instant now) {
        IdempotencyRepository.Record record = idempotency.find(scope, key)
                .orElseThrow(() -> new IllegalStateException("멱등 요청 레코드를 찾을 수 없습니다: " + scope + "/" + key));
        if (!record.expiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_EXPIRED",
                    "만료된 Idempotency-Key입니다. 새 키로 요청하세요.");
        }
        if (!record.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "같은 Idempotency-Key를 다른 결제 요청에 재사용할 수 없습니다.");
        }
        if (!record.completed()) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "같은 결제 요청이 처리 중입니다. 사유: PROCESSING");
        }
        return new Outcome(record.responseStatus(), json.read(record.responseBody(), PaymentResponse.class));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(String requesterEmail, Long paymentId) {
        User requester = requireBuyer(requesterEmail);
        PaymentRepository.PaymentSnapshot payment = payments.findPayment(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "결제를 찾을 수 없습니다."));
        if (!payment.buyerId().equals(requester.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_NOT_OWNER", "본인 결제만 조회할 수 있습니다.");
        }
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listOrderPayments(String requesterEmail, Long orderId) {
        User requester = users.findByEmail(requesterEmail).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                "AUTH_USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        if (requester.getRole() != UserRole.BUYER && requester.getRole() != UserRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "구매자 또는 운영자만 조회할 수 있습니다.");
        }
        PaymentRepository.OrderForPayment order = payments.findOrder(orderId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
        if (requester.getRole() != UserRole.ADMIN && !order.buyerId().equals(requester.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_NOT_OWNER", "조회 권한이 없습니다.");
        }
        return payments.findByOrderId(orderId, DEFAULT_LIST_LIMIT).stream().map(PaymentResponse::from).toList();
    }

    private User requireBuyer(String email) {
        User user = users.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND",
                        "사용자를 찾을 수 없습니다."));
        if (user.getRole() != UserRole.BUYER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "구매자만 결제할 수 있습니다.");
        }
        return user;
    }

    private String validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key 헤더는 필수입니다.");
        }
        if (key.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_TOO_LONG",
                    "Idempotency-Key는 200자 이하여야 합니다.");
        }
        return key;
    }

    private String hash(Long orderId, long amount) {
        String canonical = orderId + "|" + amount;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    /** replay가 채워져 있으면 PG 호출 없이 그대로 반환한다. */
    private record Preparation(Outcome replay, Long paymentId, Long attemptId, String merchantPaymentId,
                               long amount) { }

    public record Outcome(int httpStatus, PaymentResponse body) { }
}
