package com.groupdrop.outbox;

/**
 * Inbox 이벤트 소비자. 결과를 처리/무시로 구분해 돌려준다 —
 * 허용 전이표 밖의 이벤트는 실패(재시도 대상)가 아니라 무시로 종결해야 하기 때문이다 (11.5).
 */
public interface InboxHandler {

    String eventType();

    Outcome handle(InboxRepository.ClaimedEvent event);

    record Outcome(boolean processed, String reason) {

        public static Outcome handled() {
            return new Outcome(true, null);
        }

        public static Outcome ignored(String reason) {
            return new Outcome(false, reason);
        }
    }
}
