-- Fix: setting a plan again used to add another 30 included live orders each time. Included orders are now topped up to
-- 30 instead, so bring anyone above 30 back to 30 (unless an admin added orders by hand), and record the correction.
INSERT INTO live_order_charge (user_id, trade_order_id, source, orders, amount_paise, note, created_at)
SELECT id, NULL, 'ADJUST', 30 - live_orders_left, 0, 'Corrected: repeated plan grants removed (capped at 30)', CURRENT_TIMESTAMP
FROM app_user
WHERE live_orders_left > 30
  AND id NOT IN (SELECT user_id FROM live_order_charge WHERE source = 'ADJUST');

UPDATE app_user SET live_orders_left = 30, live_orders_granted = 30, live_orders_warned = FALSE
WHERE live_orders_left > 30
  AND id NOT IN (SELECT user_id FROM live_order_charge WHERE source = 'ADJUST' AND note NOT LIKE 'Corrected:%');
