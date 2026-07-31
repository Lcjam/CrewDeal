package com.groupdrop.campaign;

/**
 * 캠페인 상태 9종 (기획서 10.1). campaigns.status CHECK 제약과 값 집합이 일치해야 한다
 * (V1__base_domain.sql). 이번 주 구현 범위는 DRAFT/REVIEWING/SCHEDULED 사이의 전이만이며,
 * 나머지 상태는 이후 주차(자동 시작·종료·정산)에서 전이 로직이 추가된다.
 */
public enum CampaignStatus {
    DRAFT,
    REVIEWING,
    SCHEDULED,
    OPEN,
    SOLD_OUT,
    CLOSED,
    CANCELLED,
    SETTLING,
    SETTLED
}
