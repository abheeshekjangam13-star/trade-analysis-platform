-- Deleting a strategy or portfolio only hides it (soft delete): the row, its backtests and reports stay for the record.
-- deleted_by_user_id counts towards the Free plan's delete allowance.
ALTER TABLE strategy ADD COLUMN deleted_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE strategy ADD COLUMN deleted_by_user_id BIGINT;
ALTER TABLE portfolio ADD COLUMN deleted_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE portfolio ADD COLUMN deleted_by_user_id BIGINT;
CREATE INDEX ix_strategy_deleted_by ON strategy (deleted_by_user_id);
CREATE INDEX ix_portfolio_deleted_by ON portfolio (deleted_by_user_id);

-- Emails are sent as HTML (with the plain-text body as the alternative part).
ALTER TABLE email_notification ADD COLUMN html_body TEXT;
