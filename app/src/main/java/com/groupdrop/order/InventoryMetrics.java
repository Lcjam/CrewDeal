package com.groupdrop.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 기획서 16.4의 2주차 재고 지표. Timer는 DB 잠금 대기의 애플리케이션 근사치다. */
@Component
public class InventoryMetrics {

    private final Counter reservationSuccess;
    private final Counter soldOut;
    private final Timer inventoryUpdateDuration;

    public InventoryMetrics(MeterRegistry registry) {
        this.reservationSuccess = registry.counter("inventory.reservation.success");
        this.soldOut = registry.counter("inventory.sold.out");
        this.inventoryUpdateDuration = registry.timer("inventory.update.duration");
    }

    public void recordReservationSuccess() {
        reservationSuccess.increment();
    }

    public void recordSoldOut() {
        soldOut.increment();
    }

    public <T> T timeInventoryUpdate(Supplier<T> action) {
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            inventoryUpdateDuration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }
}
