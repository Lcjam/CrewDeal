package com.groupdrop.payment;

import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.common.Json;
import com.groupdrop.outbox.InboxHandler;
import com.groupdrop.outbox.InboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 웹훅 이벤트의 실제 적용 (PAY-04, 11.4, 11.5).
 *
 * <p>순서 역전 판정은 이벤트의 시각이 아니라 10.3 허용 전이표로 한다 — 시각 비교는 PG의 시계와
 * 전송 순서를 믿는 것이고, 전이표는 우리 상태만 믿는다. 허용되지 않는 전이를 요구하는 이벤트는
 * 실패가 아니라 무시로 종결하고 감사 로그를 남긴다.
 */
@Component
public class PaymentWebhookInboxHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookInboxHandler.class);
    private static final String SOURCE = "webhook";

    private final PaymentRepository payments;
    private final PaymentFinalizer finalizer;
    private final AuditLogRepository auditLogs;
    private final Json json;
    private final Clock clock;

    public PaymentWebhookInboxHandler(PaymentRepository payments, PaymentFinalizer finalizer,
                                      AuditLogRepository auditLogs, Json json, Clock clock) {
        this.payments = payments;
        this.finalizer = finalizer;
        this.auditLogs = auditLogs;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String eventType() {
        return PaymentWebhookController.EVENT_TYPE;
    }

    @Override
    @Transactional
    public Outcome handle(InboxRepository.ClaimedEvent event) {
        PaymentWebhookPayload payload = json.read(event.payload(), PaymentWebhookPayload.class);
        Instant now = Instant.now(clock);

        PaymentRepository.PaymentSnapshot payment = resolve(payload);
        if (payment == null) {
            return ignore(null, payload, "대응하는 결제를 찾을 수 없습니다.", now);
        }
        if (payload.amount() != null && payload.amount() != payment.amount()) {
            return ignore(payment.id(), payload,
                    "웹훅 금액(%d)이 내부 결제 금액(%d)과 다릅니다.".formatted(payload.amount(), payment.amount()), now);
        }

        return switch (String.valueOf(payload.status())) {
            case "SUCCEEDED" -> apply(payment, payload,
                    finalizer.succeed(payment, payload.providerPaymentId(), parseOccurredAt(payload), SOURCE), now);
            case "FAILED", "DECLINED" -> apply(payment, payload,
                    finalizer.fail(payment, "PG_DECLINED", "웹훅이 실패를 통지했습니다.", SOURCE), now);
            default -> ignore(payment.id(), payload,
                    "현재 상태 %s에서 허용되지 않는 전이를 요구하는 이벤트입니다: %s"
                            .formatted(payment.status(), payload.status()), now);
        };
    }

    /**
     * 성공 응답이 유실된 결제는 providerPaymentId를 모른다. 이때는 주문으로 잇는데,
     * PAY-01이 "주문당 비최종 결제 1건"을 보장하므로 지목이 유일하다. 후보가 여럿이면
     * 추측하지 않고 무시한다 — 잘못 이으면 남의 결제를 확정하는 사고가 된다.
     */
    private PaymentRepository.PaymentSnapshot resolve(PaymentWebhookPayload payload) {
        if (payload.providerPaymentId() != null && !payload.providerPaymentId().isBlank()) {
            PaymentRepository.PaymentSnapshot byProvider =
                    payments.findByProviderPaymentId(payload.providerPaymentId()).orElse(null);
            if (byProvider != null) {
                return byProvider;
            }
        }
        Long orderId = payload.numericOrderId();
        if (orderId == null) {
            return null;
        }
        List<PaymentRepository.PaymentSnapshot> candidates = payments.findNonFinalByOrderId(orderId);
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private Outcome apply(PaymentRepository.PaymentSnapshot payment, PaymentWebhookPayload payload,
                          PaymentFinalizer.Result result, Instant now) {
        if (result == PaymentFinalizer.Result.ALREADY_SETTLED) {
            // 같은 eventId 중복은 Inbox UNIQUE에서 이미 제거된다. 여기까지 온 별도 이벤트가
            // 조건부 UPDATE에 실패했다면 현재 상태에서 허용되지 않는 전이를 요구한 것이므로
            // PROCESSED가 아니라 IGNORED로 종결한다 (PAY-04, 11.5).
            return ignore(payment.id(), payload,
                    "이미 확정된 상태 %s에서 허용되지 않는 전이입니다.".formatted(payment.status()), now);
        }
        return Outcome.handled();
    }

    private Outcome ignore(Long paymentId, PaymentWebhookPayload payload, String reason, Instant now) {
        auditLogs.record(SOURCE, "WEBHOOK_IGNORED", "PAYMENT", paymentId,
                "eventId=%s status=%s: %s".formatted(payload.eventId(), payload.status(), reason), now);
        log.info("웹훅 이벤트 {}를 무시합니다: {}", payload.eventId(), reason);
        return Outcome.ignored(reason);
    }

    private Instant parseOccurredAt(PaymentWebhookPayload payload) {
        if (payload.occurredAt() == null || payload.occurredAt().isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(payload.occurredAt()).toInstant();
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
