\set ON_ERROR_STOP on

-- 17.4 docker kill 실증 후 사용: psql ... -v campaign_id=123 -f ...
-- 기대 결과: unresolved_payments=0, succeeded_without_paid=0, total_violations=0.
WITH unresolved_payments AS (
    SELECT count(*) AS violations
      FROM payments p JOIN orders o ON o.id = p.order_id
     WHERE o.campaign_id = :campaign_id
       AND p.status IN ('PROCESSING', 'UNKNOWN', 'REFUNDING')
), succeeded_without_paid AS (
    SELECT count(*) AS violations
      FROM payments p JOIN orders o ON o.id = p.order_id
     WHERE o.campaign_id = :campaign_id
       AND p.status = 'SUCCEEDED' AND o.status <> 'PAID'
), finalization_not_processed AS (
    SELECT count(*) AS violations
      FROM outbox_events oe
     WHERE oe.status <> 'PROCESSED'
       AND (
           (oe.event_type = 'payment.finalized' AND EXISTS (
               SELECT 1 FROM payments p JOIN orders o ON o.id = p.order_id
                WHERE p.id = oe.aggregate_id AND o.campaign_id = :campaign_id
           ))
           OR
           (oe.event_type = 'refund.completed' AND EXISTS (
               SELECT 1
                 FROM refunds r
                 JOIN payments p ON p.id = r.payment_id
                 JOIN orders o ON o.id = p.order_id
                WHERE r.id = oe.aggregate_id AND o.campaign_id = :campaign_id
           ))
       )
)
SELECT (SELECT violations FROM unresolved_payments) AS unresolved_payments,
       (SELECT violations FROM succeeded_without_paid) AS succeeded_without_paid,
       (SELECT violations FROM finalization_not_processed) AS finalization_not_processed,
       (SELECT violations FROM unresolved_payments)
     + (SELECT violations FROM succeeded_without_paid)
     + (SELECT violations FROM finalization_not_processed) AS total_violations;

WITH violations AS (
    SELECT 1
      FROM payments p JOIN orders o ON o.id = p.order_id
     WHERE o.campaign_id = :campaign_id
       AND p.status IN ('PROCESSING', 'UNKNOWN', 'REFUNDING')
    UNION ALL
    SELECT 1
      FROM payments p JOIN orders o ON o.id = p.order_id
     WHERE o.campaign_id = :campaign_id
       AND p.status = 'SUCCEEDED' AND o.status <> 'PAID'
    UNION ALL
    SELECT 1
      FROM outbox_events oe
     WHERE oe.status <> 'PROCESSED'
       AND (
           (oe.event_type = 'payment.finalized' AND EXISTS (
               SELECT 1 FROM payments p JOIN orders o ON o.id = p.order_id
                WHERE p.id = oe.aggregate_id AND o.campaign_id = :campaign_id
           ))
           OR
           (oe.event_type = 'refund.completed' AND EXISTS (
               SELECT 1
                 FROM refunds r
                 JOIN payments p ON p.id = r.payment_id
                 JOIN orders o ON o.id = p.order_id
                WHERE r.id = oe.aggregate_id AND o.campaign_id = :campaign_id
           ))
       )
)
SELECT 1 / CASE WHEN count(*) = 0 THEN 1 ELSE 0 END AS recovery_assertion
  FROM violations;
