-- Each GOLD/PREMIUM user can connect their own Kite Connect app (developers.kite.trade): their API key and secret,
-- and the daily access token from their Kite login. Live orders, cancels and broker views for that user go to this
-- account. The secret and the token are encrypted (AES-GCM, KiteSecrets) and never returned by any API.
CREATE TABLE kite_account (
    user_id          BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    api_key          VARCHAR(64)                 NOT NULL,
    api_secret_enc   VARCHAR(512)                NOT NULL,
    access_token_enc VARCHAR(512),
    kite_user_id     VARCHAR(32),
    kite_user_name   VARCHAR(255),
    token_updated_at TIMESTAMP(6) WITH TIME ZONE,
    created_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
-- One Kite account belongs to one platform user.
CREATE UNIQUE INDEX ux_kite_account_kite_user ON kite_account (kite_user_id);

-- Encryption key for kite_account when KITE_ENCRYPTION_KEY is not set (created once, like auth_key).
CREATE TABLE kite_account_key (
    id         SMALLINT DEFAULT 1 PRIMARY KEY,
    secret     VARCHAR(255)                NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_kite_account_key_single_row CHECK (id = 1)
);
