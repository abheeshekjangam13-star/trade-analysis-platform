-- "Target from the line" (V35) was tried and taken out: it did worse on the live portfolio's backtest. Nothing reads
-- target_spot any more, so the column goes. V35 stays in place because it has already run.
ALTER TABLE live_position DROP COLUMN IF EXISTS target_spot;
