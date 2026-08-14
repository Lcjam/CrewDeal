package com.groupdrop.mockpg;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** 런타임에 갈아끼우는 현재 장애 모드 상태. 재시작 없이 테스트 제어 API로만 바뀐다. */
@Component
public class FailureModeState {

    private final AtomicReference<FailureModeSettings> current =
            new AtomicReference<>(FailureModeSettings.normal());

    public FailureModeSettings current() {
        return current.get();
    }

    public void replace(FailureModeSettings settings) {
        current.set(settings);
    }
}
