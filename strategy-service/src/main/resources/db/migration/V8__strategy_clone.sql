-- A strategy cloned from another (e.g. a shared master strategy with the user's own lots) remembers its source.
ALTER TABLE strategy ADD COLUMN cloned_from_strategy_id BIGINT REFERENCES strategy (id) ON DELETE SET NULL;
