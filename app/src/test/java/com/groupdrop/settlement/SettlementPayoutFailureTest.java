package com.groupdrop.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 17.4 정산 지급 실패 주입.
 *
 * <p><b>한계를 먼저 적는다:</b> 지급은 앱 내부의 가상 처리(LED-04)라 외부 장애로 재현할 수 없다.
 * 여기서 실패는 테스트 프로파일 한정 플래그로 <b>주입</b>된 것이며, 따라서 이 테스트가 증명하는 것은
 * "지급이 실패할 수 있다"가 아니라 <b>실패 이후의 상태 전이</b>다 — {@code PROCESSING → FAILED},
 * 사유·재시도 횟수 기록, 그리고 재시도로 {@code COMPLETED}에 도달하는 경로(10.5).
 */
class SettlementPayoutFailureTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private SettlementService settlementService;
    @Autowired
    private SettlementRepository settlements;
    @Autowired
    private SettlementFailureInjector failureInjector;
    @Autowired
    private Clock clock;

    @AfterEach
    void disarmInjector() {
        failureInjector.disarm();
    }

    @Test
    void 주입된_지급_실패는_FAILED로_기록되고_재시도로_COMPLETED가_된다() {
        OrderFixture order = order(10, 1);
        pay(order);
        assertThat(outboxWorker.drain()).isEqualTo(1);
        closeCampaign(order.campaignId(), 8);

        failureInjector.armOnce();
        settlementService.run(ADMIN, order.campaignId());

        // 첫 배치만 실패한다 (주입은 1회 소비). 나머지 배치는 정상 지급된다.
        SettlementRepository.Batch failed = batches(order.campaignId()).stream()
                .filter(batch -> batch.status() == SettlementBatchStatus.FAILED)
                .findFirst().orElseThrow();
        assertThat(failed.failureCode()).isEqualTo("INJECTED_PAYOUT_FAILURE");
        assertThat(failed.failureReason()).contains("17.4");
        // 선점(PROCESSING)이 별도 트랜잭션으로 커밋되었기에 시도 횟수가 남는다 — 한 트랜잭션이었다면
        // 실패와 함께 롤백되어 "실패한 적 없는 배치"가 된다.
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.completedAt()).isNull();
        assertThat(count("ledger_transactions",
                "transaction_type='PAYOUT' AND reference_id=" + failed.id())).isZero();
        assertThat(count("audit_logs",
                "action='SETTLEMENT_FAILED' AND resource_id=" + failed.id())).isEqualTo(1);
        // 배치 하나가 실패했으므로 캠페인은 아직 SETTLED가 아니다.
        assertThat(campaignStatus(order.campaignId())).isEqualTo("SETTLING");

        SettlementService.SettlementBatchResponse retried = settlementService.retry(ADMIN, failed.id());

        assertThat(retried.status()).isEqualTo("COMPLETED");
        assertThat(retried.attempts()).isEqualTo(2);
        assertThat(retried.failureCode()).isNull();
        assertThat(count("ledger_transactions",
                "transaction_type='PAYOUT' AND reference_id=" + failed.id())).isEqualTo(1);
        assertThat(campaignStatus(order.campaignId())).isEqualTo("SETTLED");
    }

    @Test
    void FAILED_정산_재시도는_지급_전에_다시_대조하고_불일치면_HELD로_보낸다() {
        OrderFixture order = order(10, 1);
        pay(order);
        assertThat(outboxWorker.drain()).isEqualTo(1);
        closeCampaign(order.campaignId(), 8);

        failureInjector.armOnce();
        settlementService.run(ADMIN, order.campaignId());
        SettlementRepository.Batch failed = batches(order.campaignId()).stream()
                .filter(batch -> batch.status() == SettlementBatchStatus.FAILED)
                .findFirst().orElseThrow();
        // 실패 후 원장 합과 동결 금액이 달라진 상태를 만들어, 재시도가 verify를 우회하면 지급될 결함을 고정한다.
        jdbc.update("UPDATE settlement_batches SET total_amount=total_amount+1 WHERE id=?", failed.id());

        SettlementService.SettlementBatchResponse retried = settlementService.retry(ADMIN, failed.id());

        assertThat(retried.status()).isEqualTo("HELD");
        assertThat(retried.holdReason()).contains("배치 구성 불일치");
        assertThat(count("ledger_transactions",
                "transaction_type='PAYOUT' AND reference_id=" + failed.id())).isZero();
    }

    @Test
    void FAILED_배치도_운영자가_HELD로_보류할_수_있다() {
        OrderFixture order = order(10, 1);
        pay(order);
        assertThat(outboxWorker.drain()).isEqualTo(1);
        closeCampaign(order.campaignId(), 8);

        failureInjector.armOnce();
        settlementService.run(ADMIN, order.campaignId());
        SettlementRepository.Batch failed = batches(order.campaignId()).stream()
                .filter(batch -> batch.status() == SettlementBatchStatus.FAILED)
                .findFirst().orElseThrow();

        SettlementService.SettlementBatchResponse held = settlementService.hold(ADMIN, failed.id(), "운영자 점검");

        assertThat(held.status()).isEqualTo("HELD");
        assertThat(held.holdReason()).isEqualTo("운영자 점검");
    }

    @Test
    void 지급_중인_배치는_운영자가_보류할_수_없다() {
        OrderFixture order = order(10, 1);
        pay(order);
        outboxWorker.drain();
        closeCampaign(order.campaignId(), 8);
        settlementService.run(ADMIN, order.campaignId());

        // 10.5: hold API는 PENDING·READY 배치에만 동작한다. 실제 조건부 전이로 PROCESSING 상태를
        // 만든 뒤 검사한다. COMPLETED 배치 거부만으로는 "지급 중" 불가침을 증명하지 못한다.
        SettlementRepository.Batch completed = batches(order.campaignId()).getFirst();
        Instant now = Instant.now(clock);
        Long processingBatchId = settlements.insertBatch(order.campaignId(), completed.payeeType(),
                completed.payeeId(), BatchType.RECOVERY, SettlementBatchStatus.PENDING, -1L, now, now);
        assertThat(settlements.markReady(processingBatchId, now)).isTrue();
        assertThat(settlements.claimForProcessing(processingBatchId, now)).isTrue();
        assertThat(settlements.findBatch(processingBatchId).orElseThrow().status())
                .isEqualTo(SettlementBatchStatus.PROCESSING);

        assertThat(settlements.markHeld(processingBatchId, "지급 중 개입 시도", now)).isFalse();
    }

    private java.util.List<SettlementRepository.Batch> batches(Long campaignId) {
        return settlements.findBatchesOfCampaign(campaignId);
    }

    private String campaignStatus(Long campaignId) {
        return jdbc.queryForObject("SELECT status FROM campaigns WHERE id=?", String.class, campaignId);
    }
}
