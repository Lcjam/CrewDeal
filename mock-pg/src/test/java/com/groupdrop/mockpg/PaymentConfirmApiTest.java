package com.groupdrop.mockpg;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentConfirmApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 정상_confirm은_성공하고_merchantPaymentId로_멱등하다() throws Exception {
        String body = "{\"merchantPaymentId\":\"payment-100\",\"amount\":19900,\"orderId\":\"ord-100\"}";

        String firstProviderId = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(post("/mock-pg/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.merchantPaymentId").value("payment-100"))
                .andExpect(jsonPath("$.orderId").value("ord-100"))
                .andExpect(jsonPath("$.amount").value(19900))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty())
                .andExpect(jsonPath("$.processedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.providerPaymentId");

        mockMvc.perform(post("/mock-pg/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerPaymentId").value(firstProviderId));

        mockMvc.perform(post("/mock-pg/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantPaymentId\":\"payment-100\",\"amount\":20000,\"orderId\":\"ord-100\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/mock-pg/payments/" + firstProviderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.providerPaymentId").value(firstProviderId))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());
    }

    @Test
    void orderId가_숫자로_와도_문자열로_정규화되어_저장된다() throws Exception {
        String body = "{\"merchantPaymentId\":\"payment-200\",\"amount\":5000,\"orderId\":1024}";

        mockMvc.perform(post("/mock-pg/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("1024"));
    }
}
