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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Campaign c SET c.status = com.groupdrop.campaign.CampaignStatus.OPEN, c.updatedAt = :now "
            + "WHERE c.status = com.groupdrop.campaign.CampaignStatus.SCHEDULED AND c.startsAt <= :now")
    int openDueCampaigns(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Campaign c SET c.status = com.groupdrop.campaign.CampaignStatus.CLOSED, "
            + "c.closedAt = :now, c.updatedAt = :now "
            + "WHERE c.status IN (com.groupdrop.campaign.CampaignStatus.OPEN, "
            + "com.groupdrop.campaign.CampaignStatus.SOLD_OUT) AND c.endsAt <= :now")
    int closeEndedCampaigns(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE campaigns c
               SET status = 'SOLD_OUT', updated_at = :now
             WHERE c.status = 'OPEN'
               AND c.starts_at <= :now AND c.ends_at > :now
               AND NOT EXISTS (
                   SELECT 1
                     FROM campaign_skus cs
                     JOIN campaign_inventories ci ON ci.campaign_sku_id = cs.id
                    WHERE cs.campaign_id = c.id AND ci.available_quantity > 0
               )
            """, nativeQuery = true)
    int markAllEmptyOpenCampaignsSoldOut(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE campaigns c
               SET status = 'OPEN', updated_at = :now
             WHERE c.status = 'SOLD_OUT'
               AND c.starts_at <= :now AND c.ends_at > :now
               AND EXISTS (
                   SELECT 1
                     FROM campaign_skus cs
                     JOIN campaign_inventories ci ON ci.campaign_sku_id = cs.id
                    WHERE cs.campaign_id = c.id AND ci.available_quantity > 0
               )
            """, nativeQuery = true)
    int reopenAllAvailableSoldOutCampaigns(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Campaign c SET c.status = com.groupdrop.campaign.CampaignStatus.CANCELLED, c.updatedAt = :now "
            + "WHERE c.id = :id AND c.status = com.groupdrop.campaign.CampaignStatus.SCHEDULED")
    int cancelScheduled(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Campaign c SET c.status = com.groupdrop.campaign.CampaignStatus.CLOSED, "
            + "c.closedAt = :now, c.updatedAt = :now WHERE c.id = :id AND c.status = :from")
    int forceClose(@Param("id") Long id, @Param("from") CampaignStatus from, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE campaigns c
               SET status = 'SOLD_OUT', updated_at = :now
             WHERE c.id = :campaignId
               AND c.status = 'OPEN'
               AND NOT EXISTS (
                   SELECT 1
                     FROM campaign_skus cs
                     JOIN campaign_inventories ci ON ci.campaign_sku_id = cs.id
                    WHERE cs.campaign_id = c.id AND ci.available_quantity > 0
               )
            """, nativeQuery = true)
    int markSoldOutWhenEmpty(@Param("campaignId") Long campaignId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE campaigns c
               SET status = 'OPEN', updated_at = :now
             WHERE c.id = :campaignId
               AND c.status = 'SOLD_OUT'
               AND c.starts_at <= :now
               AND c.ends_at > :now
               AND EXISTS (
                   SELECT 1
                     FROM campaign_skus cs
                     JOIN campaign_inventories ci ON ci.campaign_sku_id = cs.id
                    WHERE cs.campaign_id = c.id AND ci.available_quantity > 0
               )
            """, nativeQuery = true)
    int reopenWhenAvailable(@Param("campaignId") Long campaignId, @Param("now") Instant now);
}
