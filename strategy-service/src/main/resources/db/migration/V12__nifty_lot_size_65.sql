-- NSE's NIFTY lot size is 65 (was 75). Strategies saved with the old default move to 65; other sizes are left alone.
UPDATE strategy_param SET param_value = '65' WHERE param_key = 'lotSize' AND param_value = '75';
