-- Each GOLD/PREMIUM user can connect their own Angel One SmartAPI app (smartapi.angelone.in) instead of a Kite app:
-- the app's API key, and the daily session from the Angel One publisher login (redirect URL = this UI's
-- /angel/callback). The tokens are encrypted (AES-GCM, KiteSecrets: same key as kite_account) and never returned by
-- any API. A user has at most one broker: kite_account or angel_account.
CREATE TABLE angel_account (
    user_id           BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    api_key           VARCHAR(64)                 NOT NULL,
    jwt_token_enc     VARCHAR(4096),
    refresh_token_enc VARCHAR(4096),
    feed_token_enc    VARCHAR(4096),
    client_code       VARCHAR(32),
    client_name       VARCHAR(255),
    token_updated_at  TIMESTAMP(6) WITH TIME ZONE,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
-- One Angel One client belongs to one platform user.
CREATE UNIQUE INDEX ux_angel_account_client ON angel_account (client_code);
