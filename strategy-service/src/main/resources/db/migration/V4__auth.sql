-- Signing key for login tokens, shared by strategy-service (issues tokens) and api-gateway (verifies them).
-- Created on first start with 48 random bytes unless AUTH_JWT_SECRET is set. Never returned by any API.
CREATE TABLE auth_key (
    id         SMALLINT DEFAULT 1 PRIMARY KEY,
    secret     VARCHAR(255)                NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_auth_key_single_row CHECK (id = 1)
);

ALTER TABLE app_user ADD COLUMN password_changed_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE app_user ADD COLUMN last_login_at       TIMESTAMP(6) WITH TIME ZONE;
