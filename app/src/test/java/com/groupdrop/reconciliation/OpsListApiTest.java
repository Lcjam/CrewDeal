package com.groupdrop.reconciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import org.junit.jupiter.api.Test;

class OpsListApiTest extends AbstractPaymentIntegrationTest {

    @Test
    void 운영_이벤트_목록은_payload_없이_필터를_검증한다() throws Exception {
        Long outboxId = jdbc.queryForObject("""
                INSERT INTO outbox_events(event_type, aggregate_type, aggregate_id, payload, status, attempts, available_at, created_at)
                VALUES ('payment.finalized', 'PAYMENT', 1, '{\"secret\":true}', 'PENDING', 0, now(), now()) RETURNING id
                """, Long.class);
        Long inboxId = jdbc.queryForObject("""
                INSERT INTO inbox_events(provider_event_id, event_type, payload, status, attempts, available_at, received_at)
                VALUES ('list-api-' || gen_random_uuid(), 'payment.status.changed', '{\"secret\":true}', 'IGNORED', 1, now(), now()) RETURNING id
                """, Long.class);

        mockMvc.perform(get("/api/admin/outbox-events").param("status", "PENDING").session(loginSession(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(outboxId));
        mockMvc.perform(get("/api/admin/inbox-events").param("status", "IGNORED").session(loginSession(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(inboxId))
                .andExpect(jsonPath("$[0].payload").doesNotExist());
        mockMvc.perform(get("/api/admin/inbox-events").param("status", "INVALID").session(loginSession(ADMIN)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATUS_FILTER"));
        mockMvc.perform(get("/api/admin/outbox-events").param("status", "PENDING,FAILED").session(loginSession(ADMIN)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/outbox-events").session(loginSession(BUYER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/inbox-events").session(loginSession(BUYER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 운영_실행이력_목록은_빈목록과_실제_실행을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/reconciliations").session(loginSession(BUYER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reconciliations").session(loginSession(ADMIN)))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/reconciliations").session(loginSession(ADMIN))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"minAgeMinutes\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/reconciliations").session(loginSession(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").exists());
    }
}
