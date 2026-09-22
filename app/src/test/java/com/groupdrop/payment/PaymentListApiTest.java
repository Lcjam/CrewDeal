package com.groupdrop.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** 프론트엔드 1단계 결제 이력·운영 목록의 인가와 읽기 계약. */
class PaymentListApiTest extends AbstractPaymentIntegrationTest {

    @Test
    void 구매자와_운영자는_주문_결제이력을_조회하고_타인은_소유권_거부된다() throws Exception {
        OrderFixture order = order(10, 1);
        long paymentId = pay(order).body().id();

        mockMvc.perform(get("/api/orders/{orderId}/payments", order.orderId()).session(loginSession(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(paymentId));
        mockMvc.perform(get("/api/orders/{orderId}/payments", order.orderId()).session(loginSession(ADMIN)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/orders/{orderId}/payments", order.orderId()).session(loginSession(INFLUENCER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mockMvc.perform(get("/api/orders/{orderId}/payments", order.orderId()).session(loginSession("buyer2@groupdrop.test")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_NOT_OWNER"));
        mockMvc.perform(get("/api/orders/{orderId}/payments", -1).session(loginSession(BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void 운영자_결제목록은_기본_UNKNOWN과_표시필드를_돌려준다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        long paymentId = pay(order).body().id();

        mockMvc.perform(get("/api/admin/payments").session(loginSession(ADMIN))
                        .param("campaignId", order.campaignId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(paymentId))
                .andExpect(jsonPath("$[0].orderId").value(order.orderId()))
                .andExpect(jsonPath("$[0].campaignId").value(order.campaignId()))
                .andExpect(jsonPath("$[0].campaignName").value("payment-campaign"))
                .andExpect(jsonPath("$[0].amount").value(order.totalAmount()));
        mockMvc.perform(get("/api/admin/payments").session(loginSession(BUYER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mockMvc.perform(get("/api/admin/payments").param("status", "NOT_A_STATUS").session(loginSession(ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_FILTER"));
    }

    @Test
    void 운영자_결제목록은_상태별_정렬_캠페인_필터_빈목록과_100건을_지킨다() throws Exception {
        OrderFixture order = order(200, 1);
        Long firstUnknownId = null;
        for (int index = 0; index < 101; index++) {
            Long paymentId = jdbc.queryForObject("""
                    INSERT INTO payments(order_id,status,amount,created_at,updated_at)
                    VALUES(?,'UNKNOWN',?,now(),now()) RETURNING id
                    """, Long.class, order.orderId(), order.totalAmount());
            if (index == 0) {
                firstUnknownId = paymentId;
            }
        }
        Long supersededId = jdbc.queryForObject("""
                INSERT INTO payments(order_id,status,amount,provider_payment_id,created_at,updated_at)
                VALUES(?,'SUPERSEDED',?,'list-superseded-' || gen_random_uuid(),now(),now()) RETURNING id
                """, Long.class, order.orderId(), order.totalAmount());

        mockMvc.perform(get("/api/admin/payments").session(loginSession(ADMIN))
                        .param("campaignId", order.campaignId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(100))
                .andExpect(jsonPath("$[0].id").value(firstUnknownId));
        mockMvc.perform(get("/api/admin/payments").session(loginSession(ADMIN)).param("status", "SUPERSEDED")
                        .param("campaignId", order.campaignId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(supersededId));
        mockMvc.perform(get("/api/admin/payments").session(loginSession(ADMIN)).param("campaignId", "999999999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
