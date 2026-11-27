-- The Nifty price a position's target is at, saved when it opens for strategies whose target is measured from the line
-- (targetFrom LINE). Null: the exit watcher works the target out from the entry, as before.
ALTER TABLE live_position ADD COLUMN target_spot DOUBLE PRECISION;
