package com.groupdrop.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupdrop.TestcontainersConfiguration;
import com.groupdrop.common.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "groupdrop.campaign-lifecycle-polling-interval=1h",
        "groupdrop.reservation-expiry-polling-interval=1h"
})
class CampaignLifecycleIntegrationTest {

    @Autowired
    private CampaignLifecycleService lifecycleService;
    @Autowired
    private CampaignLifecycleScheduler lifecycleScheduler;
    @Autowired
    private CampaignService campaignService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 자동_시작_품절_재오픈_종료는_허용_전이만_수행한다() {
        Fixture fixture = campaign("SCHEDULED", 0, Instant.now().minusSeconds(30), Instant.now().plusSeconds(3600));

        lifecycleScheduler.transitionDueCampaigns();
        assertThat(status(fixture.campaignId())).isEqualTo("SOLD_OUT");

        jdbc.update("UPDATE campaign_inventories SET available_quantity=1, initial_quantity=1 WHERE id=?",
                fixture.inventoryId());
        lifecycleScheduler.transitionDueCampaigns();
        assertThat(status(fixture.campaignId())).isEqualTo("OPEN");

        jdbc.update("UPDATE campaign_inventories SET available_quantity=0 WHERE id=?", fixture.inventoryId());
        lifecycleScheduler.transitionDueCampaigns();
        assertThat(status(fixture.campaignId())).isEqualTo("SOLD_OUT");

        jdbc.update("UPDATE campaigns SET ends_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)),
                fixture.campaignId());
        lifecycleScheduler.transitionDueCampaigns();
        assertThat(status(fixture.campaignId())).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM campaigns WHERE id=?", Boolean.class,
                fixture.campaignId())).isTrue();

        lifecycleScheduler.transitionDueCampaigns();
        assertThat(status(fixture.campaignId())).isEqualTo("CLOSED");
    }

    @Test
    void 운영자_강제종료는_SCHEDULED를_CANCELLED로_OPEN과_SOLD_OUT을_CLOSED로_전이한다() {
        Fixture scheduled = campaign("SCHEDULED", 1, Instant.now().plusSeconds(600), Instant.now().plusSeconds(3600));
        Fixture open = campaign("OPEN", 1, Instant.now().minusSeconds(10), Instant.now().plusSeconds(3600));
        Fixture soldOut = campaign("SOLD_OUT", 0, Instant.now().minusSeconds(10), Instant.now().plusSeconds(3600));

        assertThat(campaignService.cancelCampaign("admin@groupdrop.test", scheduled.campaignId()).status())
                .isEqualTo(CampaignStatus.CANCELLED);
        assertThat(campaignService.cancelCampaign("admin@groupdrop.test", open.campaignId()).status())
                .isEqualTo(CampaignStatus.CLOSED);
        assertThat(campaignService.cancelCampaign("admin@groupdrop.test", soldOut.campaignId()).status())
                .isEqualTo(CampaignStatus.CLOSED);
        assertThat(jdbc.queryForObject("SELECT closed_at < ends_at FROM campaigns WHERE id=?", Boolean.class,
                open.campaignId())).isTrue();

        assertThatThrownBy(() -> campaignService.cancelCampaign("admin@groupdrop.test", open.campaignId()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("CAMPAIGN_INVALID_TRANSITION"));
        assertThatThrownBy(() -> campaignService.cancelCampaign("buyer1@groupdrop.test", scheduled.campaignId()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("FORBIDDEN_ROLE"));
    }

    private Fixture campaign(String status, int inventory, Instant startsAt, Instant endsAt) {
        Instant now = Instant.now();
        long supplierId = jdbc.queryForObject("SELECT id FROM suppliers LIMIT 1", Long.class);
        long influencerId = jdbc.queryForObject("SELECT id FROM influencers LIMIT 1", Long.class);
        long productId = jdbc.queryForObject("INSERT INTO products(supplier_id,name,created_at) VALUES(?,?,?) RETURNING id",
                Long.class, supplierId, "campaign-lifecycle-" + UUID.randomUUID(), Timestamp.from(now));
        long skuId = jdbc.queryForObject("INSERT INTO product_skus(product_id,option_name,created_at) VALUES(?,?,?) RETURNING id",
                Long.class, productId, "option-" + UUID.randomUUID(), Timestamp.from(now));
        long campaignId = jdbc.queryForObject("""
                INSERT INTO campaigns(name,slug,influencer_id,supplier_id,product_id,status,deal_price,
                    per_user_purchase_limit,starts_at,ends_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, "lifecycle", "lifecycle-" + UUID.randomUUID(), influencerId, supplierId,
                productId, status, 19900, 10, Timestamp.from(startsAt), Timestamp.from(endsAt),
                Timestamp.from(now), Timestamp.from(now));
        long campaignSkuId = jdbc.queryForObject("INSERT INTO campaign_skus(campaign_id,product_sku_id) VALUES(?,?) RETURNING id",
                Long.class, campaignId, skuId);
        long inventoryId = jdbc.queryForObject("INSERT INTO campaign_inventories"
                + "(campaign_sku_id,initial_quantity,available_quantity) VALUES(?,?,?) RETURNING id",
                Long.class, campaignSkuId, inventory, inventory);
        long policyId = jdbc.queryForObject("INSERT INTO campaign_policy_versions"
                + "(campaign_id,version_no,commission_rate_bp,created_at) VALUES(?,1,750,?) RETURNING id",
                Long.class, campaignId, Timestamp.from(now));
        jdbc.update("INSERT INTO campaign_policy_version_items(policy_version_id,campaign_sku_id,supply_unit_price) "
                + "VALUES(?,?,1000)", policyId, campaignSkuId);
        return new Fixture(campaignId, inventoryId);
    }

    private String status(long campaignId) {
        return jdbc.queryForObject("SELECT status FROM campaigns WHERE id=?", String.class, campaignId);
    }

    private record Fixture(long campaignId, long inventoryId) { }
}
