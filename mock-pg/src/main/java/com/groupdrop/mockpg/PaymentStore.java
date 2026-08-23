package com.groupdrop.mockpg;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** merchantPaymentId 기준 인메모리 결제 저장소. providerPaymentId 조회를 위한 보조 인덱스를 함께 둔다. */
@Component
public class PaymentStore {

    private final ConcurrentMap<String, PaymentRecord> byMerchantId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PaymentRecord> byProviderId = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public String nextProviderPaymentId() {
        return "pg_" + sequence.incrementAndGet();
    }

    /**
     * merchantPaymentId가 처음 등장하면 candidate를 그대로 저장하고 {@code created=true}를 반환한다.
     * 이미 존재하면(멱등 재시도, 또는 드문 동시 요청 경합) 기존 값을 반환하고 {@code created=false}를 반환한다 —
     * 호출자는 이 플래그로 웹훅을 한 번만 쏘도록 판단한다.
     */
    public InsertResult insertIfAbsent(String merchantPaymentId, PaymentRecord candidate) {
        PaymentRecord previous = byMerchantId.putIfAbsent(merchantPaymentId, candidate);
        if (previous == null) {
            byProviderId.put(candidate.providerPaymentId(), candidate);
            return new InsertResult(candidate, true);
        }
        return new InsertResult(previous, false);
    }

    public Optional<PaymentRecord> findByMerchantId(String merchantPaymentId) {
        return Optional.ofNullable(byMerchantId.get(merchantPaymentId));
    }

    public Optional<PaymentRecord> findByProviderId(String providerPaymentId) {
        return Optional.ofNullable(byProviderId.get(providerPaymentId));
    }

    /** 대사용 전체 거래 목록 (REC-01, 14.5의 GET /mock-pg/reconciliation/transactions). */
    public java.util.List<PaymentRecord> findAll() {
        return byMerchantId.values().stream()
                .sorted(java.util.Comparator.comparing(PaymentRecord::providerPaymentId))
                .toList();
    }

    /**
     * S7 픽스처 주입 (14.5의 POST /mock-pg/test/transactions). 내부 기록과 <b>다른</b> 거래를 심는 것이
     * 목적이므로 confirm 경로를 타지 않는다 — 멱등·웹훅·장애 모드가 전부 개입해 원하는 불일치를 만들 수 없다.
     */
    public PaymentRecord inject(PaymentRecord record) {
        byMerchantId.put(record.merchantPaymentId(), record);
        byProviderId.put(record.providerPaymentId(), record);
        return record;
    }

    public record InsertResult(PaymentRecord record, boolean created) {
    }
}
