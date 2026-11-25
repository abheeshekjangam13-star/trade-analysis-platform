-- The first 1-minute candle the position's current stop applies to. The exit watcher's candle check compares a stop only
-- with candles from then on, so a stop moved to breakeven during a minute is never "hit" by that minute's earlier low.
ALTER TABLE live_position ADD COLUMN stop_from TIMESTAMP(6) WITH TIME ZONE;
