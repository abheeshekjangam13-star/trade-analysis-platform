package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.common.market.Underlying;
import com.tradeanalysis.strategy.trading.KiteApi;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Option contracts' 1-minute candles kept in table option_candle once fetched from Kite (Kite has no history for a
 * contract after it expires): what keeps past backtest trades priced with real premiums (see OptionPrices).
 */
@Component
public class OptionCandleStore {

    /** One contract's candles on one day: its trading symbol and each minute's close by the minute's start. */
    public record Day(String tradingsymbol, NavigableMap<Instant, Double> closes) {}

    private final JdbcTemplate jdbc;

    public OptionCandleStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** The stored day of the nearest-expiry contract with this strike and type, if any. */
    public Optional<Day> find(Underlying u, String optionType, int strike, LocalDate day) {
        List<String> symbols = jdbc.queryForList("""
                SELECT tradingsymbol FROM option_candle
                WHERE underlying = ? AND option_type = ? AND strike = ? AND trade_date = ?
                GROUP BY tradingsymbol, expiry ORDER BY expiry LIMIT 1""",
                String.class, u.name(), optionType, strike, day);
        if (symbols.isEmpty()) return Optional.empty();
        String symbol = symbols.get(0);
        NavigableMap<Instant, Double> closes = new TreeMap<>();
        jdbc.query("SELECT ts, close FROM option_candle WHERE tradingsymbol = ? AND trade_date = ?",
                rs -> { closes.put(rs.getTimestamp(1).toInstant(), rs.getDouble(2)); }, symbol, day);
        return Optional.of(new Day(symbol, closes));
    }

    /**
     * Stores a contract's candles of one day. Minutes already stored (e.g. by a parallel run) are skipped by the
     * database: a failed insert would otherwise spoil the backtest's whole transaction.
     */
    public void save(Underlying u, KiteApi.OptionInstrument contract, LocalDate day, List<KiteApi.Ohlc> candles) {
        if (candles.isEmpty()) return;
        List<Object[]> args = candles.stream().map(c -> new Object[]{contract.tradingsymbol(), u.name(), contract.expiry(),
                contract.strike(), contract.optionType(), day, c.start().atOffset(ZoneOffset.UTC),
                c.open(), c.high(), c.low(), c.close()}).toList();
        jdbc.batchUpdate("""
                INSERT INTO option_candle (tradingsymbol, underlying, expiry, strike, option_type, trade_date, ts,
                                           open, high, low, close)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""", args,
                new int[]{Types.VARCHAR, Types.VARCHAR, Types.DATE, Types.INTEGER, Types.VARCHAR, Types.DATE,
                        Types.TIMESTAMP_WITH_TIMEZONE, Types.DOUBLE, Types.DOUBLE, Types.DOUBLE, Types.DOUBLE});
    }
}
