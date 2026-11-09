-- Sign-up payments were recorded without their valid-until date: the plan starts when an admin approves, and approval
-- set the user's plan_expires_at but not the payment's. Copy it over for the latest paid order of the plan the user
-- now has (only where that order has no date yet, and the user's expiry is set).
UPDATE payment_order
SET plan_valid_until = (SELECT u.plan_expires_at FROM app_user u WHERE u.id = payment_order.user_id)
WHERE status = 'PAID'
  AND plan_valid_until IS NULL
  AND plan = (SELECT u.plan FROM app_user u WHERE u.id = payment_order.user_id)
  AND (SELECT u.plan_expires_at FROM app_user u WHERE u.id = payment_order.user_id) IS NOT NULL
  AND id = (SELECT MAX(q.id) FROM payment_order q
            WHERE q.user_id = payment_order.user_id AND q.plan = payment_order.plan AND q.status = 'PAID');
