package com.groupdrop.settlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 17.4의 정산 지급 실패 주입. 지급은 앱 내부의 가상 처리라(LED-04) 가상 PG 장애 모드로는 원리상
 * 재현할 수 없으므로, 지급 처리기에 실패를 심는 지점을 둔다.
 *
 * <p><b>이 테스트가 검증하는 것은 실패 자체가 아니라 실패 이후의 상태 전이</b>다
 * ({@code PROCESSING → FAILED}, 사유·재시도 기록). 주입 기반 검증이라는 한계는 주차 보고서에 명시한다.
 *
 * <p>기본값이 꺼짐이고 프로퍼티로만 켜지므로 운영 실행에서는 {@link #shouldFail()}이 항상 false다 —
 * 주입 플래그가 코드에 남아 운영에서 켜지는 사고를 막기 위해 이중 조건으로 둔다.
 */
@Component
public class SettlementFailureInjector {

    private final boolean enabled;
    private volatile boolean armed;
    private volatile String failureCode = "INJECTED_PAYOUT_FAILURE";

    public SettlementFailureInjector(
            @Value("${groupdrop.settlement-failure-injection-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /** 다음 지급 1건을 실패시킨다. 한 번 소비되면 자동으로 해제된다 — 재시도가 성공할 수 있어야 한다. */
    public void armOnce() {
        if (!enabled) {
            throw new IllegalStateException(
                    "정산 실패 주입이 비활성 상태입니다. groupdrop.settlement-failure-injection-enabled=true 필요.");
        }
        this.armed = true;
    }

    public void disarm() {
        this.armed = false;
    }

    public boolean shouldFail() {
        if (!enabled || !armed) {
            return false;
        }
        armed = false;
        return true;
    }

    public String failureCode() {
        return failureCode;
    }
}
