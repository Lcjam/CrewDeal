\set ON_ERROR_STOP on

-- 사용법: psql ... -v campaign_id=123 -f load-test/sql/inventory-invariants.sql
-- 기대 결과: inventory_balance_violations=0, reservation_cross_violations=0,
--           successful_orders=100, available=0, reserved=100, sold=0.

SELECT ci.id, ci.initial_quantity, ci.available_quantity, ci.reserved_quantity, ci.sold_quantity
  FROM campaign_inventories ci
  JOIN campaign_skus cs ON cs.id = ci.campaign_sku_id
 WHERE cs.campaign_id = :campaign_id
   AND (ci.initial_quantity <> ci.available_quantity + ci.reserved_quantity + ci.sold_quantity
        OR ci.available_quantity < 0 OR ci.reserved_quantity < 0 OR ci.sold_quantity < 0);

WITH violations AS (
    SELECT ci.id
      FROM campaign_inventories ci
      JOIN campaign_skus cs ON cs.id = ci.campaign_sku_id
     WHERE cs.campaign_id = :campaign_id
       AND (ci.initial_quantity <> ci.available_quantity + ci.reserved_quantity + ci.sold_quantity
            OR ci.available_quantity < 0 OR ci.reserved_quantity < 0 OR ci.sold_quantity < 0)
)
SELECT count(*) AS inventory_balance_violations,
       1 / CASE WHEN count(*) = 0 THEN 1 ELSE 0 END AS assertion
  FROM violations;

WITH reservation_totals AS (
    SELECT ci.id AS inventory_id,
           ci.reserved_quantity,
           ci.sold_quantity,
           COALESCE(sum(sr.quantity) FILTER (WHERE sr.status = 'ACTIVE'), 0) AS active_quantity,
           COALESCE(sum(sr.quantity) FILTER (WHERE sr.status = 'CONFIRMED'), 0) AS confirmed_quantity
      FROM campaign_inventories ci
      JOIN campaign_skus cs ON cs.id = ci.campaign_sku_id
      LEFT JOIN stock_reservations sr ON sr.campaign_inventory_id = ci.id
     WHERE cs.campaign_id = :campaign_id
     GROUP BY ci.id
), violations AS (
    SELECT * FROM reservation_totals
     WHERE reserved_quantity <> active_quantity OR sold_quantity <> confirmed_quantity
)
SELECT count(*) AS reservation_cross_violations,
       1 / CASE WHEN count(*) = 0 THEN 1 ELSE 0 END AS assertion
  FROM violations;

SELECT (SELECT count(*) FROM orders WHERE campaign_id = :campaign_id) AS successful_orders,
       (SELECT sum(total_quantity) FROM orders WHERE campaign_id = :campaign_id) AS ordered_quantity,
       (SELECT sum(ci.available_quantity) FROM campaign_inventories ci
         JOIN campaign_skus cs ON cs.id=ci.campaign_sku_id WHERE cs.campaign_id=:campaign_id) AS available,
       (SELECT sum(ci.reserved_quantity) FROM campaign_inventories ci
         JOIN campaign_skus cs ON cs.id=ci.campaign_sku_id WHERE cs.campaign_id=:campaign_id) AS reserved,
       (SELECT sum(ci.sold_quantity) FROM campaign_inventories ci
         JOIN campaign_skus cs ON cs.id=ci.campaign_sku_id WHERE cs.campaign_id=:campaign_id) AS sold;

SELECT 1 / CASE WHEN count(*) = 100 AND sum(total_quantity) = 100 THEN 1 ELSE 0 END AS s1a_order_assertion
  FROM orders
 WHERE campaign_id = :campaign_id;

WITH totals AS (
    SELECT sum(ci.available_quantity) AS available,
           sum(ci.reserved_quantity) AS reserved,
           sum(ci.sold_quantity) AS sold
      FROM campaign_inventories ci
      JOIN campaign_skus cs ON cs.id = ci.campaign_sku_id
     WHERE cs.campaign_id = :campaign_id
)
SELECT 1 / CASE WHEN available = 0 AND reserved = 100 AND sold = 0 THEN 1 ELSE 0 END
       AS s1a_inventory_assertion
  FROM totals;
