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

    private static final String SOURCE = "request";

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
        String merchantPaymentId = "mpay_" + paymentId;
        // PAY-01: 시도 기록과 READY → PROCESSING은 같은 트랜잭션이어야 한다.
        // 어긋나면 "attempt 있음 + READY" 고아가 스윕에도 대사에도 잡히지 않는다.
        Long attemptId = payments.insertAttempt(paymentId, merchantPaymentId, order.totalAmount(), now);
        if (!payments.markProcessing(paymentId, now)) {
            throw new IllegalStateException("새로 만든 결제의 READY → PROCESSING 전이에 실패했습니다: " + paymentId);
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
        int httpStatus = httpStatusFor(settled.status());
        idempotency.complete(scope, key, "PAYMENT", settled.id(), httpStatus, json.write(body), now);
        return new Outcome(httpStatus, body);
    }

    /**
     * 상태 코드로 결과를 위장하지 않는다.
     * `UNKNOWN`은 미확정이므로 202, `SUPERSEDED`는 이 요청의 결제가 주문의 유효 결제가 되지 못했으므로 409다 —
     * 200으로 답하면 환불 대상 결제를 "결제 성공"으로 통지하게 된다 (PAY-01, PAY-03).
     */
    private int httpStatusFor(String paymentStatus) {
        return switch (paymentStatus) {
            case "UNKNOWN" -> HttpStatus.ACCEPTED.value();
            case "SUPERSEDED" -> HttpStatus.CONFLICT.value();
            default -> HttpStatus.OK.value();
        };
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
