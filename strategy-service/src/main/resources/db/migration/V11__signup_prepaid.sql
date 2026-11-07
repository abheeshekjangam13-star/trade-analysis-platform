-- GOLD/PREMIUM sign-ups pay in Razorpay Checkout before an admin sees them. A verified payment for a PENDING user
-- doesn't start the plan yet: it is held here and the paid days start when the admin approves.
ALTER TABLE app_user ADD COLUMN prepaid_plan VARCHAR(20);
ALTER TABLE app_user ADD COLUMN prepaid_days INT;
