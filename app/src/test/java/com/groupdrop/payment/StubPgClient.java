package com.groupdrop.payment;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 17.4의 장애 주입을 앱 경계에서 재현하는 대역. 가상 PG(mock-pg)와 같은 규칙을 따른다 —
 * merchantPaymentId 기준 멱등이며, 한 번 PG에서 성공한 결제는 재호출 시 같은 결과를 돌려준다 (14.5).
 * 이 성질이 있어야 UNKNOWN의 조회 경로 해소를 검증할 수 있다.
 */
public class StubPgClient implements PgClient {

    public enum Mode {
        /** 정상 성공 */
        SUCCEED,
        /** 결제 거절 (명시적 실패 응답) */
        DECLINE,
        /** 성공 후 응답 유실 — PG에는 성공이 남고 호출자는 결과를 모른다 (11.2) */
        SUCCEED_BUT_TIMEOUT,
        /** PG에 아무것도 남지 않은 타임아웃 */
        TIMEOUT_NO_CHARGE
    }

    private final ConcurrentMap<String, String> chargedAtProvider = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger confirmCount = new AtomicInteger();

    private volatile Mode mode = Mode.SUCCEED;
    private volatile Runnable duringConfirm = () -> { };

    @Override
    public ConfirmResult confirm(ConfirmCommand command) {
        confirmCount.incrementAndGet();
        duringConfirm.run();

        // 이미 PG에서 승인된 건은 모드와 무관하게 같은 결과를 재생한다 (멱등).
        String existing = chargedAtProvider.get(command.merchantPaymentId());
        if (existing != null) {
            return ConfirmResult.succeeded(existing, Instant.now());
        }

        return switch (mode) {
            case SUCCEED -> ConfirmResult.succeeded(charge(command.merchantPaymentId()), Instant.now());
            case DECLINE -> ConfirmResult.failed("PG_DECLINED", "테스트 거절 모드");
            case SUCCEED_BUT_TIMEOUT -> {
                charge(command.merchantPaymentId());
                yield ConfirmResult.timeout("테스트 응답 유실 모드");
            }
            case TIMEOUT_NO_CHARGE -> ConfirmResult.timeout("테스트 타임아웃 모드 (PG 미승인)");
        };
    }

    private String charge(String merchantPaymentId) {
        return chargedAtProvider.computeIfAbsent(merchantPaymentId, key -> "pg_" + sequence.incrementAndGet());
    }

    public void reset() {
        mode = Mode.SUCCEED;
        duringConfirm = () -> { };
        chargedAtProvider.clear();
        confirmCount.set(0);
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** PG 호출 중에 다른 작업(예약 만료 등)을 끼워 넣어 경쟁을 재현한다. */
    public void setDuringConfirm(Runnable duringConfirm) {
        this.duringConfirm = duringConfirm;
    }

    public int confirmCount() {
        return confirmCount.get();
    }

    /** 해당 결제가 PG에서 승인된 상태인지. 내부 상태와 PG 상태의 불일치 검증에 쓴다. */
    public String providerPaymentIdOf(String merchantPaymentId) {
        return chargedAtProvider.get(merchantPaymentId);
    }
}
