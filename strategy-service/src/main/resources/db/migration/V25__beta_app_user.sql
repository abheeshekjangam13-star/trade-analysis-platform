-- Beta mobile app, phase 1: users an admin nominated (they get a congratulations email), and when they asked for the
-- app link on My account (the link email goes out at once).
CREATE TABLE beta_app_user (
    user_id           BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    nominated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    nominated_by      BIGINT,
    link_requested_at TIMESTAMP(6) WITH TIME ZONE,
    links_sent        INT                         DEFAULT 0 NOT NULL
);
