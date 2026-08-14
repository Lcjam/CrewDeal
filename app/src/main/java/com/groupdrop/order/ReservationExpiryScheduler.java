package com.groupdrop.order;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationExpiryScheduler {

    private final ReservationExpiryService expiryService;

    public ReservationExpiryScheduler(ReservationExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    @Scheduled(fixedDelayString = "${groupdrop.reservation-expiry-polling-interval:5s}")
    public void expireReservations() {
        expiryService.expireDueReservations();
    }
}
