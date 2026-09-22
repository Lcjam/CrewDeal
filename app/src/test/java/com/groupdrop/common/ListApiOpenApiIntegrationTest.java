package com.groupdrop.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 프론트엔드 1단계의 공개 OpenAPI 계약: 15개 목록 GET이 모두 문서에 나타난다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ListApiOpenApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 목록_GET_15개가_OpenAPI에_노출된다() throws Exception {
        var result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn();
        var paths = JsonPath.parse(result.getResponse().getContentAsString()).read("$.paths", java.util.Map.class);
        for (String path : java.util.List.of(
                "/api/campaigns", "/api/products", "/api/orders/{orderId}/payments",
                "/api/influencers/me/campaigns", "/api/suppliers/me/products", "/api/suppliers/me/campaigns",
                "/api/admin/campaigns", "/api/admin/payments", "/api/admin/settlements",
                "/api/admin/reconciliations", "/api/admin/outbox-events", "/api/admin/inbox-events",
                "/api/admin/campaigns/{id}/ledger", "/api/admin/orders/{orderId}/ledger",
                "/api/admin/ledger/unbalanced")) {
            org.assertj.core.api.Assertions.assertThat(paths).containsKey(path);
            org.assertj.core.api.Assertions.assertThat(((java.util.Map<?, ?>) paths.get(path)).get("get")).isNotNull();
        }
        for (String path : java.util.List.of(
                "/api/campaigns", "/api/products", "/api/orders/1/payments", "/api/influencers/me/campaigns",
                "/api/suppliers/me/products", "/api/suppliers/me/campaigns", "/api/admin/campaigns",
                "/api/admin/payments", "/api/admin/settlements", "/api/admin/reconciliations",
                "/api/admin/outbox-events", "/api/admin/inbox-events", "/api/admin/campaigns/1/ledger",
                "/api/admin/orders/1/ledger", "/api/admin/ledger/unbalanced")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }
}
