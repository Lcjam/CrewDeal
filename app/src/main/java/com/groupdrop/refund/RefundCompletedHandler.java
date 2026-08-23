package com.groupdrop.refund;

import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.common.Json;
import com.groupdrop.ledger.LedgerService;
import com.groupdrop.outbox.OutboxHandler;
import com.groupdrop.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code refund.completed} 소비자 (13.4). 환불 역분개(LED-03)를 기록한다.
 *
 * <p>재전달되어도 안전하다 — 역분개의 멱등은 {@code ledger_transactions}의 참조 유니크가 보장한다.
 *
 * <p>{@code SUPERSEDED} 보상 환불은 LED-02 원본이 없으므로 역분개 없이 종결한다 (PAY-01).
 * 이 판정을 페이로드의 {@code compensation} 플래그가 아니라 <b>원본 거래의 존재</b>로 하는 이유는,
 * 원장이 답을 갖고 있는데 이벤트 필드를 믿을 이유가 없기 때문이다.
 */
@Component
public class RefundCompletedHandler implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundCompletedHandler.class);
    private static final String SOURCE = "refund-ledger";

    private final LedgerService ledger;
    private final AuditLogRepository auditLogs;
    private final Json json;
    private final Clock clock;

    public RefundCompletedHandler(LedgerService ledger, AuditLogRepository auditLogs, Json json, Clock clock) {
        this.ledger = ledger;
        this.auditLogs = auditLogs;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String eventType() {
        return RefundExecutionWorker.EVENT_REFUND_COMPLETED;
    }

    @Override
    @Transactional
    public void handle(OutboxRepository.ClaimedEvent event) {
        RefundExecutionWorker.RefundCompletedPayload payload =
                json.read(event.payload(), RefundExecutionWorker.RefundCompletedPayload.class);
        Instant now = Instant.now(clock);
        Instant occurredAt = parseOccurredAt(payload.occurredAt(), now);

        boolean reversed = ledger.recordRefundReversal(payload.refundId(), payload.paymentId(), occurredAt, now);
        if (!reversed) {
            auditLogs.record(SOURCE, "REFUND_REVERSAL_SKIPPED", "REFUND", payload.refundId(),
                    "원장에 결제 %d의 원본 거래가 없어 역분개를 생략했습니다.".formatted(payload.paymentId()), now);
            log.info("환불 {}의 역분개를 생략했습니다 (원본 거래 없음 또는 이미 기록됨).", payload.refundId());
        }

        // SET-03 회수 배치는 settlement_item이 존재하는 건에만 생성한다. 정산 테이블이 생기는
        // 5주차에 이 지점에 연결한다 (13.4). 지금은 지급된 적이 없으므로 회수 대상도 없다.
    }

    private Instant parseOccurredAt(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
