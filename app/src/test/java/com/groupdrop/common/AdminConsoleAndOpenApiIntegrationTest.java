package com.groupdrop.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 6주차 운영 콘솔 정적 셸과 springdoc 공개 문서 경로 계약. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminConsoleAndOpenApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 운영_콘솔_정적_셸과_OpenAPI_문서는_비인증으로_로드되지만_운영_API는_인증을_요구한다() throws Exception {
        mockMvc.perform(get("/admin/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("admin@groupdrop.test")));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/admin/ops/summary")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/admin/reconciliation-discrepancies")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/admin/settlements/{batchId}")));

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));

        mockMvc.perform(get("/api/admin/ops/summary"))
                .andExpect(status().isUnauthorized());
    }
}
