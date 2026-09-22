package com.groupdrop.settlement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 1단계 운영자 정산 목록 계약. */
class SettlementAdminListApiTest extends AbstractPaymentIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private SettlementRepository settlements;

    @Test
    void 운영자는_캠페인명과_상태_필터가_적용된_정산_목록을_조회한다() throws Exception {
        OrderFixture order = order(10, 1);
        Long supplierId = jdbc.queryForObject("SELECT id FROM suppliers LIMIT 1", Long.class);
        Long heldBatchId = settlements.insertBatch(order.campaignId(), PayeeType.SUPPLIER, supplierId, BatchType.SETTLEMENT,
                SettlementBatchStatus.HELD, 1_000, Instant.now(clock), Instant.now(clock));
        jdbc.update("UPDATE settlement_batches SET hold_reason='미해결 대사 불일치 2건' WHERE id=?", heldBatchId);

        mockMvc.perform(get("/api/admin/settlements").session(loginSession(ADMIN))
                        .param("campaignId", order.campaignId().toString()).param("status", "HELD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].campaignId").value(order.campaignId()))
                .andExpect(jsonPath("$[0].campaignName").value("payment-campaign"))
                .andExpect(jsonPath("$[0].batchType").value("SETTLEMENT"))
                .andExpect(jsonPath("$[0].payeeType").value("SUPPLIER"))
                .andExpect(jsonPath("$[0].holdReason").value("미해결 대사 불일치 2건"));

        // campaignId와 status가 모두 없으면 동적 조건 없이 전체 상태를 목록으로 읽는다.
        mockMvc.perform(get("/api/admin/settlements").session(loginSession(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").exists());
    }

    @Test
    void 복수_상태_필터와_100건_제한을_적용한다() throws Exception {
        OrderFixture order = order(10, 1);
        Long supplierId = jdbc.queryForObject("SELECT id FROM suppliers LIMIT 1", Long.class);
        settlements.insertBatch(order.campaignId(), PayeeType.SUPPLIER, supplierId, BatchType.SETTLEMENT,
                SettlementBatchStatus.HELD, 1_000, Instant.now(clock), Instant.now(clock));
        for (int index = 0; index < 101; index++) {
            settlements.insertBatch(order.campaignId(), PayeeType.SUPPLIER, supplierId, BatchType.RECOVERY,
                    SettlementBatchStatus.FAILED, -1, Instant.now(clock), Instant.now(clock));
        }

        mockMvc.perform(get("/api/admin/settlements").session(loginSession(ADMIN))
                        .param("campaignId", order.campaignId().toString()).param("status", "HELD,FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(100))
                .andExpect(jsonPath("$[0].status").value("FAILED"));
    }

    @Test
    void 빈_목록과_ADMIN_권한을_검증한다() throws Exception {
        mockMvc.perform(get("/api/admin/settlements").session(loginSession(ADMIN)).param("campaignId", "999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/admin/settlements").session(loginSession(BUYER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 잘못된_정산_상태_필터는_400이다() throws Exception {
        mockMvc.perform(get("/api/admin/settlements").session(loginSession(ADMIN)).param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_FILTER"));
    }
}
