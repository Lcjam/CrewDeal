package com.groupdrop.campaign;

/**
 * 캠페인 상태 9종 (기획서 10.1). campaigns.status CHECK 제약과 값 집합이 일치해야 한다
 * (V1__base_domain.sql). 승인 흐름은 CampaignService, 자동 시작·종료·품절 표시는
 * CampaignLifecycleService(CAM-03), SETTLING·SETTLED는 settlement 패키지(SET-01·02)가 전이시킨다.
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
