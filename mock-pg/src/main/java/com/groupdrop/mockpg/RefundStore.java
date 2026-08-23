package com.groupdrop.mockpg;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * providerPaymentId 기준 인메모리 환불 저장소. 기획서 14.5 "동시 요청 포함 환불 실행 최대 1회" 요구를
 * {@link ConcurrentMap#computeIfAbsent}로 보장한다 — 조회 후 없으면 넣는 check-then-act가 아니라,
 * 같은 키에 대해 매핑 함수(실제 환불 실행)가 원자적으로 최대 1회만 적용된다.
 */
@Component
public class RefundStore {

    private final ConcurrentMap<String, RefundRecord> byProviderPaymentId = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public String nextProviderRefundId() {
        return "rf_" + sequence.incrementAndGet();
    }

    public Optional<RefundRecord> find(String providerPaymentId) {
        return Optional.ofNullable(byProviderPaymentId.get(providerPaymentId));
    }

    /**
     * providerPaymentId에 대한 환불을 최대 1회만 실행한다. 이미 존재하면 creator를 호출하지 않고 기존 값을
     * 반환하며 {@code created=false}, 이번 호출로 처음 만들어졌으면 {@code created=true}를 반환한다 —
     * 호출자는 이 플래그로 장애 모드 적용 여부(첫 실행에만 적용)를 판단한다.
     */
    public ExecutionResult executeOnce(String providerPaymentId, Supplier<RefundRecord> creator) {
        boolean[] created = {false};
        RefundRecord record = byProviderPaymentId.computeIfAbsent(providerPaymentId, key -> {
            created[0] = true;
            return creator.get();
        });
        return new ExecutionResult(record, created[0]);
    }

    /** 대사용 전체 환불 목록. 결제 거래에 환불 금액을 붙여 내보내기 위해 쓴다 (REC-01의 환불 금액 비교). */
    public java.util.List<RefundRecord> findAll() {
        return java.util.List.copyOf(byProviderPaymentId.values());
    }

    /** S7 픽스처 주입. 내부에는 없는 환불을 PG에만 심어 REFUND_MISMATCH를 만들 수 있어야 한다. */
    public RefundRecord inject(RefundRecord record) {
        byProviderPaymentId.put(record.providerPaymentId(), record);
        return record;
    }

    public record ExecutionResult(RefundRecord record, boolean created) {
    }
}
