package com.groupdrop.campaign;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    Optional<Campaign> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * 상태 전이는 항상 이 조건부 UPDATE로만 수행한다 — 엔티티 setter로 전이하지 않는다
     * (app/CLAUDE.md 코딩 규칙). 영향 행 0은 전이표 위반 또는 동시 전이 충돌을 뜻한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Campaign c SET c.status = :to, c.updatedAt = :now WHERE c.id = :id AND c.status = :from")
    int transitionStatus(@Param("id") Long id,
                          @Param("from") CampaignStatus from,
                          @Param("to") CampaignStatus to,
                          @Param("now") Instant now);

    /** REVIEWING → DRAFT 반려 전이 전용 — 사유를 같은 조건부 UPDATE에서 함께 기록한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Campaign c SET c.status = :to, c.rejectionReason = :reason, c.updatedAt = :now "
            + "WHERE c.id = :id AND c.status = :from")
    int rejectTransition(@Param("id") Long id,
                          @Param("from") CampaignStatus from,
                          @Param("to") CampaignStatus to,
                          @Param("reason") String reason,
                          @Param("now") Instant now);
}
