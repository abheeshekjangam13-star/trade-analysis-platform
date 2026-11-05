-- Current Kite Connect session (one row). Written when a token is generated/updated so a restart keeps it and
-- strategy-service (trading) can use the same session. Never returned by any API or written to logs.
CREATE TABLE kite_session (
    id           SMALLINT DEFAULT 1 PRIMARY KEY,
    api_key      VARCHAR(64)                 NOT NULL,
    access_token VARCHAR(255)                NOT NULL,
    user_id      VARCHAR(32),
    user_name    VARCHAR(255),
    updated_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_kite_session_single_row CHECK (id = 1)
);
