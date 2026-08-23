package com.groupdrop.refund;

import com.groupdrop.common.ApiException;
import com.groupdrop.common.GroupdropProperties;
import com.groupdrop.common.IdempotencyRepository;
import com.groupdrop.common.Json;
import com.groupdrop.payment.PaymentRepository;
import com.groupdrop.user.User;
import com.groupdrop.user.UserRepository;
import com.groupdrop.user.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REF-02 전체 환불의 요청 스레드 경로. 실행 주체는 MVP에서 운영자 한정이다.
 *
 * <p>이 서비스는 <b>접수만</b> 한다 — PG 호출은 {@link RefundExecutionWorker}가 한다 (13.4).
 * 요청 스레드가 PG를 직접 부르면 접수 커밋과 PG 호출 사이의 크래시에서 환불이 유실된다.
 * 그래서 응답은 200이 아니라 202(접수)이며, 결과는 조회 API로 확인한다.
 */
@Service
public class RefundService {

    /** REF-02 환불 기한: 캠페인 종료 후 30일. */
    static final Duration REFUND_WINDOW = Duration.ofDays(30);

    private static final String SOURCE = "admin";

    private final UserRepository users;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final RefundInitiator initiator;
    private final IdempotencyRepository idempotency;
    private final GroupdropProperties properties;
    private final TransactionTemplate transactions;
    private final Json json;
    private final Clock clock;

    public RefundService(UserRepository users, PaymentRepository payments, RefundRepository refunds,
                         RefundInitiator initiator, IdempotencyRepository idempotency,
                         GroupdropProperties properties, TransactionTemplate transactions,
                         Json json, Clock clock) {
        this.users = users;
        this.payments = payments;
        this.refunds = refunds;
        this.initiator = initiator;
        this.idempotency = idempotency;
        this.properties = properties;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
    }

    public Outcome requestRefund(String requesterEmail, Long paymentId, String idempotencyKey,
                                 CreateRefundRequest request) {
        requireAdmin(requesterEmail);
        String key = validateIdempotencyKey(idempotencyKey);
        String scope = "POST:/api/payments/" + paymentId + "/refunds";
        return transactions.execute(status -> accept(paymentId, scope, key, request));
    }

    private Outcome accept(Long paymentId, String scope, String key, CreateRefundRequest request) {
        Instant now = Instant.now(clock);
        PaymentRepository.PaymentSnapshot payment = payments.findPayment(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "결제를 찾을 수 없습니다."));

        String requestHash = hash(paymentId, payment.amount());
        if (!idempotency.claim(scope, key, requestHash, now, now.plus(properties.idempotencyKeyTtl()))) {
            return replayOrReject(scope, key, requestHash, now);
        }

        // 상태 검증은 선점 뒤에 한다. 그래야 같은 키의 재요청이 "처리 중" 또는 저장된 응답으로 수렴하고,
        // 매번 새로 검증해 서로 다른 결과를 내지 않는다 (PAY-02).
        if (!"SUCCEEDED".equals(payment.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_REFUNDABLE",
                    "성공한 결제만 환불할 수 있습니다. 현재 상태: " + payment.status());
        }
        if (refunds.hasEffectiveRefund(paymentId)) {
            throw new ApiException(HttpStatus.CONFLICT, "REFUND_ALREADY_EXISTS",
                    "이 결제에는 이미 유효한 환불이 있습니다.");
        }
        requireWithinRefundWindow(payment.orderId(), now);

        Long refundId;
        try {
            refundId = initiator.initiate(paymentId, payment.orderId(), payment.amount(),
                    request == null ? null : request.reason(), SOURCE);
        } catch (DuplicateKeyException exception) {
            // refunds(payment_id) WHERE status <> 'FAILED' 부분 유니크 (13.2). 위 선조회와의 경쟁 창을
            // DB가 닫는다 — 애플리케이션 검사만으로는 동시 요청 두 건이 모두 통과할 수 있다.
            throw new ApiException(HttpStatus.CONFLICT, "REFUND_ALREADY_EXISTS",
                    "이 결제에는 이미 유효한 환불이 있습니다.");
        } catch (RefundInitiator.RefundNotAcceptableException exception) {
            // 결제 상태 전이 경쟁에서 진 요청. 500이 아니라 409로 답해야 클라이언트가 재시도를 판단할 수 있다.
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_REFUNDABLE",
                    "이 결제는 이미 환불이 접수되었거나 성공 상태가 아닙니다.");
        }

        RefundResponse body = RefundResponse.from(refunds.find(refundId).orElseThrow());
        idempotency.complete(scope, key, "REFUND", refundId, HttpStatus.ACCEPTED.value(), json.write(body), now);
        return new Outcome(HttpStatus.ACCEPTED.value(), body);
    }

    /** 기한이 없으면 회수 배치가 무기한 열려 있는 시스템이 된다 (REF-02). */
    private void requireWithinRefundWindow(Long orderId, Instant now) {
        Instant endsAt = refunds.findCampaignEndsAt(orderId)
                .orElseThrow(() -> new IllegalStateException("주문의 캠페인을 찾을 수 없습니다: " + orderId));
        if (now.isAfter(endsAt.plus(REFUND_WINDOW))) {
            throw new ApiException(HttpStatus.CONFLICT, "REFUND_WINDOW_CLOSED",
                    "환불 기한(캠페인 종료 후 30일)이 지났습니다.");
        }
    }

    /** PAY-02와 같은 세 갈래 — 만료·해시 불일치·처리 중·완료 재생. */
    private Outcome replayOrReject(String scope, String key, String requestHash, Instant now) {
        IdempotencyRepository.Record record = idempotency.find(scope, key)
                .orElseThrow(() -> new IllegalStateException("멱등 요청 레코드를 찾을 수 없습니다: " + scope + "/" + key));
        if (!record.expiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_EXPIRED",
                    "만료된 Idempotency-Key입니다. 새 키로 요청하세요.");
        }
        if (!record.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "같은 Idempotency-Key를 다른 환불 요청에 재사용할 수 없습니다.");
        }
        if (!record.completed()) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "같은 환불 요청이 처리 중입니다.");
        }
        return new Outcome(record.responseStatus(), json.read(record.responseBody(), RefundResponse.class));
    }

    /** 환불 상태 조회. 운영자와 해당 주문의 구매자만 볼 수 있다. */
    @Transactional(readOnly = true)
    public List<RefundResponse> listRefunds(String requesterEmail, Long paymentId) {
        User requester = users.findByEmail(requesterEmail)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND",
                        "사용자를 찾을 수 없습니다."));
        PaymentRepository.PaymentSnapshot payment = payments.findPayment(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "결제를 찾을 수 없습니다."));
        boolean allowed = requester.getRole() == UserRole.ADMIN
                || payment.buyerId().equals(requester.getId());
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_NOT_OWNER", "조회 권한이 없습니다.");
        }
        return refunds.findByPayment(paymentId).stream().map(RefundResponse::from).toList();
    }

    private void requireAdmin(String email) {
        User user = users.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND",
                        "사용자를 찾을 수 없습니다."));
        if (user.getRole() != UserRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE",
                    "환불 실행은 운영자만 할 수 있습니다 (REF-02).");
        }
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

    private String hash(Long paymentId, long amount) {
        String canonical = paymentId + "|" + amount;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    public record Outcome(int httpStatus, RefundResponse body) { }
}
