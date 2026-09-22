package com.groupdrop.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import com.groupdrop.refund.CreateRefundRequest;
import com.groupdrop.refund.RefundService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Transactional;

/** 1단계 운영자 원장 목록 계약. */
class LedgerAdminApiTest extends AbstractPaymentIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private RefundService refunds;

    @Test
    void 운영자는_캠페인_잔액과_주문별_거래_분개를_조회한다() throws Exception {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        while (outboxWorker.drain() > 0) {
            // 결제·환불 이벤트를 모두 원장까지 반영한다.
        }
        refunds.requestRefund(ADMIN, paymentId, UUID.randomUUID().toString(), new CreateRefundRequest("테스트 환불"));
        while (outboxWorker.drain() > 0) {
            // refund.requested → refund.completed → 원장 역분개
        }
        MockHttpSession admin = loginSession(ADMIN);

        mockMvc.perform(get("/api/admin/campaigns/{campaignId}/ledger", order.campaignId()).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(order.campaignId()))
                .andExpect(jsonPath("$.balances.SUPPLIER_PAYABLE").value(0))
                .andExpect(jsonPath("$.debitTotal").value(39_800))
                .andExpect(jsonPath("$.creditTotal").value(39_800));

        mockMvc.perform(get("/api/admin/orders/{orderId}/ledger", order.orderId()).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transactionType").value("REFUND"))
                .andExpect(jsonPath("$[0].referenceType").value("REFUND"))
                .andExpect(jsonPath("$[0].side").value("CREDIT"))
                .andExpect(jsonPath("$[0].occurredAt").exists())
                .andExpect(jsonPath("$[0].accountCode").exists());
    }

    @Test
    void 주문별_목록은_최신_100개_거래의_모든_분개를_반환한다() throws Exception {
        OrderFixture order = order(200, 1);
        Long debitAccountId = jdbc.queryForObject("SELECT id FROM ledger_accounts WHERE code='PG_RECEIVABLE'", Long.class);
        Long creditAccountId = jdbc.queryForObject("SELECT id FROM ledger_accounts WHERE code='PLATFORM_REVENUE'", Long.class);
        Long oldestTransactionId = null;
        for (int index = 0; index < 101; index++) {
            Long transactionId = jdbc.queryForObject("""
                    INSERT INTO ledger_transactions
                        (transaction_type, reference_type, reference_id, campaign_id, order_id, occurred_at, created_at)
                    VALUES ('PAYMENT', 'PAYMENT', ?, ?, ?, now(), now()) RETURNING id
                    """, Long.class, -20_000L - index, order.campaignId(), order.orderId());
            if (index == 0) {
                oldestTransactionId = transactionId;
            }
            jdbc.update("INSERT INTO ledger_entries(transaction_id,account_id,side,amount) VALUES(?,?,'DEBIT',1)",
                    transactionId, debitAccountId);
            jdbc.update("INSERT INTO ledger_entries(transaction_id,account_id,side,amount) VALUES(?,?,'CREDIT',1)",
                    transactionId, creditAccountId);
        }

        mockMvc.perform(get("/api/admin/orders/{orderId}/ledger", order.orderId()).session(loginSession(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(200))
                .andExpect(jsonPath("$[?(@.transactionId == " + oldestTransactionId + ")]").isEmpty());
    }

    @Test
    void 비어있는_주문_원장과_운영자_전용_원장_API를_검증한다() throws Exception {
        OrderFixture order = order(10, 1);

        mockMvc.perform(get("/api/admin/orders/{orderId}/ledger", order.orderId()).session(loginSession(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/admin/campaigns/{id}/ledger", order.campaignId()).session(loginSession(BUYER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mockMvc.perform(get("/api/admin/orders/{orderId}/ledger", order.orderId()).session(loginSession(BUYER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    @Transactional
    void 불균형_거래를_100건까지만_운영자에게_노출한다() throws Exception {
        for (int index = 0; index < 101; index++) {
            jdbc.update("""
                    INSERT INTO ledger_transactions
                        (transaction_type, reference_type, reference_id, campaign_id, order_id, occurred_at, created_at)
                    VALUES ('PAYMENT', 'PAYMENT', ?, NULL, NULL, now(), now())
                    """, -10_000L - index);
        }

        mockMvc.perform(get("/api/admin/ledger/unbalanced").session(loginSession(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(100))
                .andExpect(jsonPath("$[0].debitTotal").value(0))
                .andExpect(jsonPath("$[0].creditTotal").value(0));
        mockMvc.perform(get("/api/admin/ledger/unbalanced").session(loginSession(BUYER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }
}
