package com.groupdrop.outbox;

/**
 * Outbox 이벤트 소비자. 리스 기반 at-least-once 전달이므로 구현은 반드시 멱등해야 한다 —
 * 효과는 조건부 UPDATE로 표현하고, 이미 반영된 이벤트의 재처리는 조용히 no-op이어야 한다.
 */
public interface OutboxHandler {

    String eventType();

    void handle(OutboxRepository.ClaimedEvent event);
}
