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

/** 웹훅 수신 스레드는 Inbox 적재까지만 하고, 실제 처리는 이 워커가 비동기로 수행한다 (PAY-04). */
@Component
public class InboxWorker {

    private static final Logger log = LoggerFactory.getLogger(InboxWorker.class);
    private static final int MAX_ATTEMPTS = 10;

    private final InboxRepository repository;
    private final Map<String, InboxHandler> handlers;
    private final MessagingMetrics metrics;
    private final GroupdropProperties properties;
    private final Clock clock;

    public InboxWorker(InboxRepository repository, List<InboxHandler> handlers,
                       MessagingMetrics metrics, GroupdropProperties properties, Clock clock) {
        this.repository = repository;
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(InboxHandler::eventType, Function.identity()));
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${groupdrop.inbox-polling-interval}")
    public void poll() {
        try {
            drain();
        } catch (RuntimeException exception) {
            log.error("Inbox 폴링에 실패했습니다.", exception);
        }
    }

    /** 테스트가 시각을 제어하며 직접 호출한다. 처리(무시 포함)한 이벤트 수를 반환한다. */
    public int drain() {
        Instant now = Instant.now(clock);
        Instant leaseUntil = now.plus(properties.messageLeaseDuration());
        List<InboxRepository.ClaimedEvent> claimed =
                repository.claim(now, leaseUntil, properties.messageBatchSize());
        int settled = 0;
        for (InboxRepository.ClaimedEvent event : claimed) {
            if (dispatch(event, now)) {
                settled++;
            }
        }
        return settled;
    }

    private boolean dispatch(InboxRepository.ClaimedEvent event, Instant now) {
        InboxHandler handler = handlers.get(event.eventType());
        if (handler == null) {
            repository.markFailed(event.id(), "핸들러가 없는 이벤트 타입: " + event.eventType(), now);
            log.error("Inbox 이벤트 {}의 핸들러가 없습니다: {}", event.id(), event.eventType());
            return false;
        }
        try {
            InboxHandler.Outcome outcome = handler.handle(event);
            if (outcome.processed()) {
                repository.markProcessed(event.id(), now);
                metrics.recordInboxProcessed(event.eventType());
            } else {
                repository.markIgnored(event.id(), outcome.reason(), now);
                metrics.recordInboxIgnored(event.eventType());
            }
            return true;
        } catch (RuntimeException exception) {
            handleFailure(event, exception, now);
            return false;
        }
    }

    private void handleFailure(InboxRepository.ClaimedEvent event, RuntimeException exception, Instant now) {
        metrics.recordInboxFailure(event.eventType());
        if (event.attempts() >= MAX_ATTEMPTS) {
            repository.markFailed(event.id(), exception.toString(), now);
            log.error("Inbox 이벤트 {}를 {}회 시도 후 FAILED로 종결합니다.", event.id(), event.attempts(), exception);
            return;
        }
        log.warn("Inbox 이벤트 {} 처리 실패 — 재시도합니다 (시도 {}회).", event.id(), event.attempts(), exception);
        repository.markRetry(event.id(), exception.toString(), now.plus(properties.messageRetryBackoff()));
    }
}
