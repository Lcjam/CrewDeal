package com.groupdrop.mockpg;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대사용 거래 목록 조회 (14.5의 {@code GET /mock-pg/reconciliation/transactions}).
 *
 * <p>결제 기록과 환불 기록을 <b>거래 1건으로 합쳐</b> 내보낸다. REC-01이 비교하는 필드가
 * 결제 금액·환불 금액·결제 상태·거래 발생 시각이라, 두 목록을 따로 주면 대사 쪽에서 다시 조립해야 하고
 * 그 조립 규칙이 PG와 대사에 이중으로 존재하게 된다.
 */
@RestController
@RequestMapping("/mock-pg/reconciliation")
public class ReconciliationController {

    private final PaymentStore paymentStore;
    private final RefundStore refundStore;
    private final Clock clock;

    public ReconciliationController(PaymentStore paymentStore, RefundStore refundStore, Clock clock) {
        this.paymentStore = paymentStore;
        this.refundStore = refundStore;
        this.clock = clock;
    }

    /**
     * @param createdBefore 이 시각 이전에 처리된 거래만 반환한다 (REC-01의 최소 경과 시간). 생략하면 전체.
     */
    @GetMapping("/transactions")
    public TransactionsResponse transactions(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore) {
        Instant cutoff = createdBefore == null ? Instant.now(clock) : createdBefore;
        Map<String, RefundRecord> refundsByPayment = refundStore.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(RefundRecord::providerPaymentId, record -> record,
                        (first, second) -> first));

        List<ProviderTransaction> transactions = paymentStore.findAll().stream()
                .filter(record -> !record.processedAt().isAfter(cutoff))
                .map(record -> toTransaction(record, refundsByPayment.get(record.providerPaymentId())))
                .toList();
        return new TransactionsResponse(transactions.size(), transactions);
    }

    private ProviderTransaction toTransaction(PaymentRecord payment, RefundRecord refund) {
        return new ProviderTransaction(payment.providerPaymentId(), payment.merchantPaymentId(),
                payment.orderId(), payment.amount(), payment.status(), payment.processedAt(),
                refund == null ? 0L : refund.amount(),
                refund == null ? null : refund.providerRefundId(),
                refund == null ? null : refund.refundedAt());
    }

    /** 대사가 읽는 거래 1건. 필드 집합은 REC-01의 비교 필드에서 나온 것이다. */
    public record ProviderTransaction(String providerPaymentId, String merchantPaymentId, String orderId,
            long amount, String status, Instant processedAt, long refundedAmount, String providerRefundId,
            Instant refundedAt) {
    }

    public record TransactionsResponse(int count, List<ProviderTransaction> transactions) {
    }

}
