package com.groupdrop.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupdrop.common.ApiException;
import com.groupdrop.order.OrderResponse;
import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import com.groupdrop.payment.StubPgClient;
import org.junit.jupiter.api.Test;

/**
 * REF-01 주문 취소 (9.4, 14.2).
 *
 * <p>핵심 규칙은 "결제 완료 전"이 아니라 "결제 <b>결과가 확정</b>되었고 성공이 아닐 때"다 —
 * {@code PROCESSING}·{@code UNKNOWN} 결제를 두고 취소하면 성공 결제가 붙은 취소 주문이 생긴다.
 */
class OrderCancellationTest extends AbstractPaymentIntegrationTest {

    @Test
    void 결제_전_주문을_취소하면_예약이_풀리고_재고와_구매카운터가_복구된다() {
        OrderFixture order = order(10, 2);
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 8, 2, 0);

        OrderResponse cancelled = orderService.cancelOrder(BUYER, order.orderId());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 10, 0, 0);
        assertThat(count("stock_reservations", "status='RELEASED' AND order_item_id IN"
                + " (SELECT id FROM order_items WHERE order_id=" + order.orderId() + ")")).isEqualTo(1);
        assertThat(purchaseCounter(order.campaignId())).isZero();
    }

    @Test
    void 취소를_반복해도_같은_결과를_돌려준다() {
        OrderFixture order = order(10, 1);
        orderService.cancelOrder(BUYER, order.orderId());

        OrderResponse again = orderService.cancelOrder(BUYER, order.orderId());

        assertThat(again.status()).isEqualTo("CANCELLED");
        // 재고와 카운터가 두 번 복구되지 않는다.
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 10, 0, 0);
        assertThat(purchaseCounter(order.campaignId())).isZero();
    }

    @Test
    void 결제_실패_후에는_취소할_수_있다() {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.DECLINE);
        Long paymentId = pay(order).body().id();
        assertThat(paymentStatus(paymentId)).isEqualTo("FAILED");

        OrderResponse cancelled = orderService.cancelOrder(BUYER, order.orderId());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 10, 0, 0);
    }

    @Test
    void 결과_불명_결제가_있으면_취소를_거부하고_확정을_기다리게_한다() {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");

        assertThatThrownBy(() -> orderService.cancelOrder(BUYER, order.orderId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PAYMENT_NOT_SETTLED");

        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        // 예약도 그대로 유지된다 — 성공으로 확정될 수 있는 결제의 재고를 미리 풀지 않는다.
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 9, 1, 0);
    }

    @Test
    void 결제가_완료된_주문은_취소가_아니라_환불_대상이다() {
        OrderFixture order = order(10, 1);
        pay(order);
        assertThat(outboxWorker.drain()).isEqualTo(1);
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");

        assertThatThrownBy(() -> orderService.cancelOrder(BUYER, order.orderId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ORDER_NOT_CANCELLABLE");
    }

    @Test
    void 남의_주문은_취소할_수_없다() {
        OrderFixture order = order(10, 1);

        assertThatThrownBy(() -> orderService.cancelOrder("buyer2@groupdrop.test", order.orderId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("FORBIDDEN_NOT_OWNER");
    }

    private int purchaseCounter(Long campaignId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM campaign_user_purchase_counters WHERE campaign_id=?
                """, Integer.class, campaignId);
    }
}
