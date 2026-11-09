package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.engine.MarketDataClient;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Estimated NIFTY option premiums, for "capital used" on past trades and for the strategy preview. Backtests only
 * know NIFTY spot (Kite has no prices for expired options), so the option bought (the trade's strike) is priced with
 * Black-Scholes, using that day's India VIX as volatility and the time left to that week's NIFTY expiry. Paper and live
 * trades know their real premium; this is for the rest. Estimates, not quotes: real premiums also carry spreads and skew.
 */
@Component
public class OptionEstimates {

    private static final Logger log = LoggerFactory.getLogger(OptionEstimates.class);
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final String VIX = "INDIA VIX";
    /** Risk-free rate (approx. Indian T-bill yield). */
    static final double RATE = 0.065;
    /** Volatility when India VIX isn't available for a day (labelled as assumed). */
    public static final double DEFAULT_IV = 0.14;
    /** NSE moved NIFTY's weekly expiry from Thursday to Tuesday from September 2025. */
    static final LocalDate TUESDAY_EXPIRY_FROM = LocalDate.of(2025, 9, 1);

    private final MarketDataClient marketData;

    public OptionEstimates(MarketDataClient marketData) { this.marketData = marketData; }

    // ── Pricing ──────────────────────────────────────────────────────────────

    /** Black-Scholes price of a European call or put; at or after expiry, the intrinsic value. */
    public static double price(boolean call, double spot, double strike, double years, double iv) {
        if (years <= 0 || iv <= 0) return Math.max(0, call ? spot - strike : strike - spot);
        double sd = iv * Math.sqrt(years);
        double d1 = (Math.log(spot / strike) + (RATE + iv * iv / 2) * years) / sd, d2 = d1 - sd;
        double df = Math.exp(-RATE * years);
        return call ? spot * cdf(d1) - strike * df * cdf(d2) : strike * df * cdf(-d2) - spot * cdf(-d1);
    }

    /** Standard normal CDF (Abramowitz–Stegun 26.2.17, error < 7.5e-8). */
    static double cdf(double x) {
        double t = 1 / (1 + 0.2316419 * Math.abs(x));
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double tail = Math.exp(-x * x / 2) / Math.sqrt(2 * Math.PI) * poly;
        return x >= 0 ? 1 - tail : tail;
    }

    /** That week's NIFTY expiry on or after {@code day} (Thursday, Tuesday from Sept 2025; holidays not shifted). */
    public static LocalDate weeklyExpiry(LocalDate day) {
        return day.with(TemporalAdjusters.nextOrSame(day.isBefore(TUESDAY_EXPIRY_FROM) ? DayOfWeek.THURSDAY : DayOfWeek.TUESDAY));
    }

    /** Years from {@code at} to the expiry's 15:30 IST close, at least 30 minutes. */
    public static double yearsToExpiry(Instant at, LocalDate expiry) {
        long secs = Duration.between(at, expiry.atTime(15, 30).atZone(IST).toInstant()).getSeconds();
        return Math.max(1800, secs) / (365.0 * 86_400);
    }

    /** The strike the strategy buys: CE at ATM−50, PE at ATM+50 (ATM = spot rounded to 50). */
    public static int strike(boolean call, double spot) {
        int atm = (int) (Math.round(spot / 50.0) * 50);
        return call ? atm - 50 : atm + 50;
    }

    // ── India VIX ────────────────────────────────────────────────────────────

    /** Volatility (VIX/100) per IST day in [from, to], from India VIX daily candles: each day's open. Empty if unavailable. */
    public Map<LocalDate, Double> vix(LocalDate from, LocalDate to) {
        Map<LocalDate, Double> out = new TreeMap<>();
        try {
            PriceSeries s = marketData.getRange(VIX, Interval.DAY_1, from.minusDays(10).atStartOfDay(IST).toInstant(),
                    to.plusDays(1).atStartOfDay(IST).toInstant());
            for (Candle c : s.candles()) out.put(c.timestamp().atZone(IST).toLocalDate(), c.open().doubleValue() / 100);
        } catch (RuntimeException e) {
            log.warn("India VIX not available for {}..{}: {} (using {}% volatility)", from, to, e.getMessage(), DEFAULT_IV * 100);
        }
        return out;
    }

    /** That day's volatility, else the latest day before it; null if none. */
    static Double ivOn(Map<LocalDate, Double> vix, LocalDate day) {
        if (vix.isEmpty()) return null;
        Map.Entry<LocalDate, Double> e = ((TreeMap<LocalDate, Double>) vix).floorEntry(day);
        return e == null ? null : e.getValue();
    }

    // ── Trades ───────────────────────────────────────────────────────────────

    /**
     * Fills each trade's estimated entry premium and capital used (premium × lots × lot size), and its share of the
     * capital of the backtest it came from ({@code capitalByBacktest}; missing = no percentage).
     */
    public void estimate(List<NiftyTradeLog> trades, Map<Long, Double> capitalByBacktest) {
        if (trades.isEmpty()) return;
        LocalDate first = trades.stream().map(NiftyTradeLog::getTradeDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate last = trades.stream().map(NiftyTradeLog::getTradeDate).max(Comparator.naturalOrder()).orElseThrow();
        Map<LocalDate, Double> vix = vix(first, last);
        for (NiftyTradeLog t : trades) {
            Double iv = ivOn(vix, t.getTradeDate());
            boolean assumed = iv == null;
            double vol = assumed ? DEFAULT_IV : iv;
            Instant entry = t.getEntryTimestamp() != null ? t.getEntryTimestamp()
                    : t.getTradeDate().atTime(9, 20).atZone(IST).toInstant();   // older rows may lack the entry time
            double years = yearsToExpiry(entry, weeklyExpiry(t.getTradeDate()));
            double premium = price("CE".equals(t.getOptionType()), t.getEntrySpotPrice(), t.getStrikePrice(), years, vol);
            t.estimated(premium, capitalByBacktest.get(t.getBacktestResultId()), vol, assumed);
        }
    }
}
