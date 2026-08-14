-- 17.3 2계층: Outbox·Inbox 다중 워커 소비의 정합성 검증.
-- 한 건이라도 위반이 있으면 마지막 SELECT가 0이 아닌 값을 돌려준다.
\set ON_ERROR_STOP on

\echo '== 이벤트 처리 상태 =='
SELECT status, count(*) AS events FROM outbox_events GROUP BY status ORDER BY status;
SELECT status, count(*) AS events FROM inbox_events GROUP BY status ORDER BY status;

\echo '== 대상 캠페인 주문·결제 상태 =='
SELECT o.status AS order_status, count(*) AS orders
  FROM orders o WHERE o.campaign_id = :campaign_id
 GROUP BY o.status ORDER BY o.status;
SELECT p.status AS payment_status, count(*) AS payments
  FROM payments p JOIN orders o ON o.id = p.order_id
 WHERE o.campaign_id = :campaign_id
 GROUP BY p.status ORDER BY p.status;

\echo '== 불변식 위반 건수 (모두 0이어야 함) =='
WITH
-- 미처리로 남은 이벤트
pending_outbox AS (
    SELECT count(*) AS violations FROM outbox_events WHERE status <> 'PROCESSED'
),
pending_inbox AS (
    SELECT count(*) AS violations FROM inbox_events WHERE status NOT IN ('PROCESSED', 'IGNORED')
),
-- 12.1 재고 불변식: 초기 = 가용 + 예약 + 판매
inventory_balance AS (
    SELECT count(*) AS violations
      FROM campaign_inventories ci JOIN campaign_skus cs ON cs.id = ci.campaign_sku_id
     WHERE cs.campaign_id = :campaign_id
       AND ci.initial_quantity <> ci.available_quantity + ci.reserved_quantity + ci.sold_quantity
),
-- 판매 수량은 확정된 예약 수량과 일치해야 한다. 이벤트가 두 번 소비되면 여기가 어긋난다.
sold_vs_confirmed AS (
    SELECT count(*) AS violations
      FROM campaign_inventories ci
      JOIN campaign_skus cs ON cs.id = ci.campaign_sku_id
      LEFT JOIN (
          SELECT sr.campaign_inventory_id, sum(sr.quantity) AS confirmed
            FROM stock_reservations sr WHERE sr.status = 'CONFIRMED'
           GROUP BY sr.campaign_inventory_id
      ) c ON c.campaign_inventory_id = ci.id
     WHERE cs.campaign_id = :campaign_id
       AND ci.sold_quantity <> COALESCE(c.confirmed, 0)
),
-- 12.2: 한 주문에 유효한 성공 결제는 최대 1건
duplicate_success AS (
    SELECT count(*) AS violations FROM (
        SELECT p.order_id FROM payments p JOIN orders o ON o.id = p.order_id
         WHERE o.campaign_id = :campaign_id
           AND p.status IN ('SUCCEEDED', 'REFUNDING', 'REFUNDED')
         GROUP BY p.order_id HAVING count(*) > 1
    ) d
),
-- PAID 주문의 예약은 전부 CONFIRMED여야 한다
paid_without_confirmed AS (
    SELECT count(*) AS violations
      FROM orders o JOIN order_items oi ON oi.order_id = o.id
      JOIN stock_reservations sr ON sr.order_item_id = oi.id
     WHERE o.campaign_id = :campaign_id AND o.status = 'PAID' AND sr.status <> 'CONFIRMED'
),
-- 성공 결제가 있는데 주문이 PAID가 아닌 경우 (11.6 경쟁 결과인 REFUNDING은 제외)
succeeded_without_paid AS (
    SELECT count(*) AS violations
      FROM payments p JOIN orders o ON o.id = p.order_id
     WHERE o.campaign_id = :campaign_id AND p.status = 'SUCCEEDED'
       AND o.status NOT IN ('PAID', 'REFUNDING')
)
SELECT (SELECT violations FROM pending_outbox)        AS pending_outbox,
       (SELECT violations FROM pending_inbox)         AS pending_inbox,
       (SELECT violations FROM inventory_balance)     AS inventory_balance,
       (SELECT violations FROM sold_vs_confirmed)     AS sold_vs_confirmed,
       (SELECT violations FROM duplicate_success)     AS duplicate_success,
       (SELECT violations FROM paid_without_confirmed) AS paid_without_confirmed,
       (SELECT violations FROM succeeded_without_paid) AS succeeded_without_paid,
       (SELECT violations FROM pending_outbox)
     + (SELECT violations FROM pending_inbox)
     + (SELECT violations FROM inventory_balance)
     + (SELECT violations FROM sold_vs_confirmed)
     + (SELECT violations FROM duplicate_success)
     + (SELECT violations FROM paid_without_confirmed)
     + (SELECT violations FROM succeeded_without_paid) AS total_violations;
