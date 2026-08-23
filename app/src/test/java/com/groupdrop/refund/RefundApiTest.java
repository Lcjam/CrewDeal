package com.groupdrop.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.common.ApiException;
import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import com.groupdrop.payment.StubPgClient;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/** REF-02 전체 환불 API (14.2, 운영자 전용 + 멱등 키). */
class RefundApiTest extends AbstractPaymentIntegrationTest {

    private static final String SEED_PASSWORD = "groupdrop123!";
    private static final String ADMIN = "admin@groupdrop.test";

    @Autowired
    private RefundService refundService;

    @Test
    void 운영자_환불_요청은_202로_접수되고_PG_호출은_워커가_한다() throws Exception {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);

        MockHttpSession admin = login(ADMIN);
        mockMvc.perform(post("/api/payments/{id}/refunds", paymentId)
                        .session(admin)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"구매자 요청\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.amount").value(DEAL_PRICE))
                .andExpect(jsonPath("$.compensation").value(false));

        // 접수만 됐고 PG에는 아직 나가지 않았다 (13.4).
        assertThat(pgClient.refundCount()).isZero();
        assertThat(count("outbox_events", "event_type='refund.requested' AND status='PENDING'"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void 같은_멱등키의_환불_재요청은_저장된_응답을_재생하고_환불을_또_만들지_않는다() throws Exception {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);
        MockHttpSession admin = login(ADMIN);
        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(refundRequest(paymentId, admin, key))
                .andExpect(status().isAccepted())
                .andReturn();
        MvcResult second = mockMvc.perform(refundRequest(paymentId, admin, key))
                .andExpect(status().isAccepted())
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(count("refunds", "payment_id=" + paymentId)).isEqualTo(1);
    }

    @Test
    void 구매자는_환불을_실행할_수_없다() throws Exception {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);

        mockMvc.perform(refundRequest(paymentId, login(BUYER), UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        assertThat(count("refunds", "payment_id=" + paymentId)).isZero();
    }

    @Test
    void 성공하지_않은_결제는_환불할_수_없다() {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.DECLINE);
        Long paymentId = pay(order).body().id();
        assertThat(paymentStatus(paymentId)).isEqualTo("FAILED");

        assertThatThrownBy(() -> requestRefund(paymentId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PAYMENT_NOT_REFUNDABLE");
    }

    @Test
    void 이미_환불이_접수된_결제에는_두번째_환불을_만들지_않는다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);
        requestRefund(paymentId);

        // 접수와 동시에 결제가 REFUNDING으로 넘어가므로 두 번째 요청은 상태 게이트에서 막힌다 (10.3).
        assertThatThrownBy(() -> requestRefund(paymentId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PAYMENT_NOT_REFUNDABLE");
        assertThat(count("refunds", "payment_id=" + paymentId + " AND status <> 'FAILED'")).isEqualTo(1);
    }

    @Test
    void 서로_다른_멱등키의_동시_환불_요청도_유효한_환불을_하나만_남긴다() throws Exception {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<String> results = new CopyOnWriteArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        requestRefund(paymentId);
                        results.add("ACCEPTED");
                    } catch (ApiException exception) {
                        results.add(exception.getCode());
                    } catch (RuntimeException | InterruptedException exception) {
                        results.add("OTHER:" + exception.getClass().getSimpleName());
                    }
                });
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        }

        // 12.2: 성공한 결제당 유효한 환불은 최대 1건. 선조회를 통과한 경쟁자는 부분 유니크가 막는다 (13.2).
        assertThat(count("refunds", "payment_id=" + paymentId + " AND status <> 'FAILED'")).isEqualTo(1);
        assertThat(results).hasSize(threads).contains("ACCEPTED");
        assertThat(results).allSatisfy(result -> assertThat(result)
                .isIn("ACCEPTED", "REFUND_ALREADY_EXISTS", "PAYMENT_NOT_REFUNDABLE"));
    }

    @Test
    void 부분_유니크_제약이_동시_환불_요청의_두번째를_막는다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);
        requestRefund(paymentId);

        // 애플리케이션 선조회를 건너뛴 직접 INSERT도 DB가 막는다 (13.2).
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO refunds(payment_id,order_id,status,amount,compensation,requested_at,updated_at)
                VALUES(?,?,'REQUESTED',?,FALSE,now(),now())
                """, paymentId, order.orderId(), order.totalAmount()))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void 캠페인_종료_후_30일이_지나면_환불할_수_없다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);
        // 기한이 없으면 회수 배치가 무기한 열려 있는 시스템이 된다 (REF-02).
        jdbc.update("""
                UPDATE campaigns SET starts_at = now() - interval '40 days', ends_at = now() - interval '31 days'
                 WHERE id=?
                """, order.campaignId());

        assertThatThrownBy(() -> requestRefund(paymentId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("REFUND_WINDOW_CLOSED");
    }

    @Test
    void 환불_상태는_운영자와_해당_구매자만_조회할_수_있다() throws Exception {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);
        requestRefund(paymentId);

        mockMvc.perform(get("/api/payments/{id}/refunds", paymentId).session(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("REQUESTED"));
        mockMvc.perform(get("/api/payments/{id}/refunds", paymentId).session(login(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("REQUESTED"));
        mockMvc.perform(get("/api/payments/{id}/refunds", paymentId).session(login("buyer2@groupdrop.test")))
                .andExpect(status().isForbidden());
    }

    // ---- 헬퍼 ----

    private org.springframework.test.web.servlet.RequestBuilder refundRequest(Long paymentId,
                                                                              MockHttpSession session, String key) {
        return post("/api/payments/{id}/refunds", paymentId)
                .session(session)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"구매자 요청\"}");
    }

    private Long requestRefund(Long paymentId) {
        return refundService.requestRefund(ADMIN, paymentId, UUID.randomUUID().toString(),
                new CreateRefundRequest("테스트 환불")).body().id();
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
