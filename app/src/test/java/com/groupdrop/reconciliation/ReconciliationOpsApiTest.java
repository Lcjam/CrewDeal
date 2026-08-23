package com.groupdrop.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import com.groupdrop.payment.StubPgClient;
import com.groupdrop.settlement.SettlementService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/** REC-02 운영자 재처리와 16.4 운영 요약 API (14.4). */
class ReconciliationOpsApiTest extends AbstractPaymentIntegrationTest {

    private static final String SEED_PASSWORD = "groupdrop123!";

    @Autowired
    private ReconciliationRepository repository;
    @Autowired
    private SettlementService settlementService;

    @Test
    void 운영자는_최소_경과_0으로_대사를_실행하고_실행_기록을_조회할_수_있다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");

        MockHttpSession admin = login(ADMIN);
        MvcResult result = mockMvc.perform(post("/api/admin/reconciliations")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minAgeMinutes\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.minAgeMinutes").value(0))
                .andReturn();

        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        long runId = com.jayway.jsonpath.JsonPath.parse(result.getResponse().getContentAsString())
                .read("$.id", Integer.class).longValue();
        mockMvc.perform(get("/api/admin/reconciliations/{runId}", runId).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId));
        assertThat(count("audit_logs", "action='RECONCILIATION_TRIGGERED'")).isPositive();
    }

    @Test
    void 대사_실행은_운영자만_할_수_있다() throws Exception {
        mockMvc.perform(post("/api/admin/reconciliations")
                        .session(login(BUYER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minAgeMinutes\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 미해결_불일치를_조회하고_재처리하면_확정과_함께_해소된다() throws Exception {
        OrderFixture order = order(10, 1);
        // PG에 아무것도 남지 않은 타임아웃 → 대사도 확정하지 못해 UNRESOLVED_INTERNAL로 등록된다.
        pgClient.setMode(StubPgClient.Mode.TIMEOUT_NO_CHARGE);
        Long paymentId = pay(order).body().id();

        MockHttpSession admin = login(ADMIN);
        mockMvc.perform(post("/api/admin/reconciliations").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minAgeMinutes\":0}"))
                .andExpect(status().isOk());

        Long discrepancyId = openDiscrepancyId(paymentId);
        mockMvc.perform(get("/api/admin/reconciliation-discrepancies").session(admin)
                        .param("status", "OPEN"))
                .andExpect(status().isOk());

        // PG가 정상으로 돌아온 뒤 재처리하면 내부 상태가 복구되고 그 자리에서 해소로 닫힌다.
        pgClient.setMode(StubPgClient.Mode.SUCCEED);
        mockMvc.perform(post("/api/admin/reconciliation-discrepancies/{id}/retry", discrepancyId)
                        .session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(count("audit_logs", "action='DISCREPANCY_RETRIED' AND resource_id=" + discrepancyId))
                .isEqualTo(1);
    }

    @Test
    void 해결_메모_없이는_불일치를_닫을_수_없다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.TIMEOUT_NO_CHARGE);
        Long paymentId = pay(order).body().id();

        MockHttpSession admin = login(ADMIN);
        mockMvc.perform(post("/api/admin/reconciliations").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minAgeMinutes\":0}"))
                .andExpect(status().isOk());
        Long discrepancyId = openDiscrepancyId(paymentId);

        mockMvc.perform(post("/api/admin/reconciliation-discrepancies/{id}/resolve", discrepancyId)
                        .session(admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESOLUTION_NOTE_REQUIRED"));

        mockMvc.perform(post("/api/admin/reconciliation-discrepancies/{id}/resolve", discrepancyId)
                        .session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"PG 담당자 확인 후 수기 처리\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").value("PG 담당자 확인 후 수기 처리"));
    }

    @Test
    void 운영자_결제_동기화는_PG_재조회로_내부_상태를_확정한다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");

        mockMvc.perform(post("/api/admin/payments/{id}/sync", paymentId).session(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        assertThat(count("audit_logs", "action='PAYMENT_SYNCED' AND resource_id=" + paymentId)).isEqualTo(1);
    }

    @Test
    void 운영_요약은_16_4의_핵심_수치를_돌려준다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        pay(order);

        mockMvc.perform(get("/api/admin/ops/summary").session(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unknownPaymentCount").isNumber())
                .andExpect(jsonPath("$.oldestOutboxAgeSeconds").isNumber())
                .andExpect(jsonPath("$.openDiscrepancyCount").isNumber())
                .andExpect(jsonPath("$.blockedSettlementCount").isNumber())
                .andExpect(jsonPath("$.unrecoveredAdjustmentCount").isNumber());

        // 방금 만든 UNKNOWN 결제가 수치에 반영된다.
        mockMvc.perform(get("/api/admin/ops/summary").session(login(ADMIN)))
                .andExpect(jsonPath("$.unknownPaymentCount",
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void 수령_주체는_자기_확정_정산_내역만_조회한다() throws Exception {
        OrderFixture order = order(10, 1);
        pay(order);
        outboxWorker.drain();
        closeCampaign(order.campaignId(), 8);
        settlementService.run(ADMIN, order.campaignId());

        mockMvc.perform(get("/api/influencers/me/settlements").session(login(INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payeeType").value("INFLUENCER"));
        mockMvc.perform(get("/api/suppliers/me/settlements").session(login(SUPPLIER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payeeType").value("SUPPLIER"));
        // 역할이 다르면 자기 목록이 아니다.
        mockMvc.perform(get("/api/influencers/me/settlements").session(login(SUPPLIER)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/settlement-adjustments").session(login(ADMIN)))
                .andExpect(status().isOk());
    }

    // ---- 헬퍼 ----

    private Long openDiscrepancyId(Long paymentId) {
        List<ReconciliationRepository.Discrepancy> open = repository.findDiscrepancies("OPEN", 500).stream()
                .filter(discrepancy -> paymentId.equals(discrepancy.paymentId()))
                .toList();
        assertThat(open).hasSize(1);
        return open.getFirst().id();
    }

    private MockHttpSession login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + SEED_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
