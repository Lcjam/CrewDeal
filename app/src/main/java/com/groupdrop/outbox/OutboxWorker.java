package com.groupdrop.outbox;

import com.groupdrop.common.GroupdropProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * in-process 폴링 워커. 분산 락 없이 다중 인스턴스에서 안전해야 하며, 그 근거는
 * 리스 청구의 FOR UPDATE SKIP LOCKED와 핸들러의 멱등성 두 가지뿐이다 (17.3 2계층에서 실증).
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int MAX_ATTEMPTS = 10;

    private final OutboxRepository repository;
    private final Map<String, OutboxHandler> handlers;
    private final MessagingMetrics metrics;
    private final GroupdropProperties properties;
    private final Clock clock;

    public OutboxWorker(OutboxRepository repository, List<OutboxHandler> handlers,
                        MessagingMetrics metrics, GroupdropProperties properties, Clock clock) {
        this.repository = repository;
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(OutboxHandler::eventType, Function.identity()));
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${groupdrop.outbox-polling-interval}")
    public void poll() {
        try {
            drain();
        } catch (RuntimeException exception) {
            log.error("Outbox 폴링에 실패했습니다.", exception);
        }
    }

    /** 테스트가 시각을 제어하며 직접 호출한다. 처리한 이벤트 수를 반환한다. */
    public int drain() {
        Instant now = Instant.now(clock);
        try {
            Instant leaseUntil = now.plus(properties.messageLeaseDuration());
            List<OutboxRepository.ClaimedEvent> claimed =
                    repository.claim(now, leaseUntil, properties.messageBatchSize());
            int processed = 0;
            for (OutboxRepository.ClaimedEvent event : claimed) {
                if (dispatch(event, now)) {
                    processed++;
                }
            }
            return processed;
        } finally {
            updatePendingMetrics(now);
        }
    }

    private void updatePendingMetrics(Instant now) {
        OutboxRepository.PendingStats stats = repository.pendingStats();
        long ageSeconds = stats.oldestCreatedAt() == null ? 0L
                : Math.max(0L, now.getEpochSecond() - stats.oldestCreatedAt().getEpochSecond());
        metrics.updateOutboxPending(stats.count(), ageSeconds);
    }

    private boolean dispatch(OutboxRepository.ClaimedEvent event, Instant now) {
        OutboxHandler handler = handlers.get(event.eventType());
        if (handler == null) {
            repository.markFailed(event.id(), "핸들러가 없는 이벤트 타입: " + event.eventType(), now);
            log.error("Outbox 이벤트 {}의 핸들러가 없습니다: {}", event.id(), event.eventType());
            return false;
        }
        try {
            handler.handle(event);
            repository.markProcessed(event.id(), now);
            metrics.recordOutboxProcessed(event.eventType());
            return true;
        } catch (RuntimeException exception) {
            handleFailure(event, exception, now);
            return false;
        }
    }

    private void handleFailure(OutboxRepository.ClaimedEvent event, RuntimeException exception, Instant now) {
        metrics.recordOutboxFailure(event.eventType());
        if (event.attempts() >= MAX_ATTEMPTS) {
            repository.markFailed(event.id(), exception.toString(), now);
            log.error("Outbox 이벤트 {}를 {}회 시도 후 FAILED로 종결합니다.", event.id(), event.attempts(), exception);
            return;
        }
        log.warn("Outbox 이벤트 {} 처리 실패 — 재시도합니다 (시도 {}회).", event.id(), event.attempts(), exception);
        repository.markRetry(event.id(), exception.toString(), now.plus(properties.messageRetryBackoff()));
    }
}
