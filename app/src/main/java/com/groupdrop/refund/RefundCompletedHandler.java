package com.groupdrop.refund;

import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.common.Json;
import com.groupdrop.ledger.LedgerService;
import com.groupdrop.outbox.OutboxHandler;
import com.groupdrop.outbox.OutboxRepository;
import com.groupdrop.settlement.SettlementRecoveryService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 *
 * <p>SET-03 회수 배치도 여기에 붙는다. 별도 핸들러를 만들지 않는 이유는 취향이 아니라 제약이다 —
 * 워커는 핸들러를 {@code eventType} 키로 색인하므로 같은 타입의 핸들러가 둘이면 빈 생성 단계에서
 * 중복 키로 애플리케이션이 뜨지 않는다.
 */
@Component
public class RefundCompletedHandler implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundCompletedHandler.class);
    private static final String SOURCE = "refund-ledger";

    private final LedgerService ledger;
    private final SettlementRecoveryService recoveries;
    private final AuditLogRepository auditLogs;
    private final Json json;
    private final Clock clock;

    public RefundCompletedHandler(LedgerService ledger, SettlementRecoveryService recoveries,
                                  AuditLogRepository auditLogs, Json json, Clock clock) {
        this.ledger = ledger;
        this.recoveries = recoveries;
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

        // SET-03: 원 주문 항목이 지급 배치에 실제 포함된 건이면 회수 배치를 만들어 즉시 실행한다.
        // 역분개와 같은 트랜잭션이어야 "환불은 반영됐는데 회수는 없는" 상태가 생기지 않는다.
        List<Long> recoveryBatchIds = recoveries.recoverForRefund(payload.refundId(), payload.orderId());
        if (!recoveryBatchIds.isEmpty()) {
            auditLogs.record(SOURCE, "RECOVERY_BATCH_CREATED", "REFUND", payload.refundId(),
                    "회수 배치 %s를 생성·실행했습니다 (SET-03).".formatted(recoveryBatchIds), now);
        }
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
