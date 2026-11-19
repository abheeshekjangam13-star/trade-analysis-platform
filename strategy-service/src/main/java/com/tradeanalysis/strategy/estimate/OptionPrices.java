package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.common.market.Underlying;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.trading.KiteApi;
import com.tradeanalysis.strategy.trading.NiftyOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/**
 * Prices backtest trades with the real premiums of the option live trading would have bought, instead of the
 * index-move estimate: the nearest-expiry contract from the trade's day at the trade's strike and type (as
 * LiveStrategyRunner resolves it), bought when the signal candle closed and sold when the exit minute ended.
 *
 * <p>The contract's 1-minute candles come from table option_candle, else from Kite, which only has them while the
 * contract is listed; a complete day fetched from Kite is stored so the trade stays priced after expiry. A trade whose
 * contract had expired before its candles were stored, or when Kite isn't connected, keeps the estimate.
 */
@Component
public class OptionPrices {

    private static final Logger log = LoggerFactory.getLogger(OptionPrices.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9, 15), CLOSE = LocalTime.of(15, 30);

    private final NiftyOptions options;
    private final KiteApi kite;
    private final OptionCandleStore store;
    private final Clock clock;

    public OptionPrices(NiftyOptions options, KiteApi kite, OptionCandleStore store, Clock clock) {
        this.options = options; this.kite = kite; this.store = store; this.clock = clock;
    }

    record Key(Underlying underlying, String optionType, int strike, LocalDate day) {}

    /** Strategy parameter: slippage allowance in option points per side (else {@link #defaultSlippage}). */
    public static final String SLIPPAGE_PARAM = "slippagePoints";

    /**
     * The slippage allowance in option points per side when a strategy sets none, from Usha's Kite fills against the
     * option's minute closes (Nov 2026): NIFTY fills within about a point, Bank Nifty's several points worse.
     */
    public static double defaultSlippage(Underlying u) {
        return u == Underlying.BANKNIFTY ? 5.0 : 1.0;
    }

    /** {@link #apply(List, Double)} with each trade's own allowance (stored rows keep theirs; else the default). */
    public void apply(List<NiftyTradeLog> trades) { apply(trades, null); }

    /**
     * Gives each trade its slippage allowance and prices each trade it can (see {@link NiftyTradeLog#priceWith}); the
     * rest keep the estimate (which pays the allowance too).
     *
     * @param slippagePoints the strategy's allowance in option points per side; null = the trade's stored one, else
     *                       {@link #defaultSlippage} for its index
     */
    public void apply(List<NiftyTradeLog> trades, Double slippagePoints) {
        if (trades.isEmpty()) return;
        Map<Key, Optional<OptionCandleStore.Day>> days = new HashMap<>();
        boolean[] kiteDown = {false};
        int priced = 0;
        for (NiftyTradeLog t : trades) {
            t.useSlippage(slippagePoints != null ? slippagePoints
                    : t.getSlippage() != null ? t.getSlippage() : defaultSlippage(Underlying.of(t.getSymbol())));
            if (t.getOptionType() == null || t.getTradeDate() == null || t.getEntryTimestamp() == null) continue;
            Key key = new Key(Underlying.of(t.getSymbol()), t.getOptionType(), t.getStrikePrice(), t.getTradeDate());
            Optional<OptionCandleStore.Day> day = days.computeIfAbsent(key, k -> day(k, kiteDown));
            if (day.isEmpty()) continue;
            t.priceWith(day.get().tradingsymbol(), day.get().closes(), enteredAt(t));
            if (t.getEntryPremium() != null) priced++;
        }
        log.info("OPTION_PRICES trades={} realPrices={} contractDays={}", trades.size(), priced,
                days.values().stream().filter(Optional::isPresent).count());
    }

    /**
     * When live trading would have bought: as the signal candle closed. Engines decide on 5-minute candles, stamp the
     * entry with the candle's start and take its close as the entry price (LiveStrategyRunner orders 5 minutes on too).
     */
    static Instant enteredAt(NiftyTradeLog t) { return t.getEntryTimestamp().plusSeconds(5 * 60); }

    /** The contract's candles that day: stored ones, else Kite's while the contract is listed (empty if neither). */
    private Optional<OptionCandleStore.Day> day(Key k, boolean[] kiteDown) {
        Optional<OptionCandleStore.Day> stored = store.find(k.underlying(), k.optionType(), k.strike(), k.day());
        if (stored.isPresent()) return stored;
        if (kiteDown[0]) return Optional.empty();
        try {
            Optional<KiteApi.OptionInstrument> contract = contract(options.all(k.underlying()), k);
            if (contract.isEmpty()) return Optional.empty();
            Instant now = clock.instant();
            Instant from = k.day().atTime(OPEN).atZone(IST).toInstant(), close = k.day().atTime(CLOSE).atZone(IST).toInstant();
            List<KiteApi.Ohlc> candles = kite.minuteCandles(contract.get().instrumentToken(), from, close.isAfter(now) ? now : close);
            if (candles.isEmpty()) return Optional.empty();
            if (!now.isBefore(close)) store.save(k.underlying(), contract.get(), k.day(), candles);   // only whole days
            NavigableMap<Instant, Double> closes = new TreeMap<>();
            for (KiteApi.Ohlc c : candles) closes.put(c.start(), c.close());
            return Optional.of(new OptionCandleStore.Day(contract.get().tradingsymbol(), closes));
        } catch (RuntimeException e) {
            kiteDown[0] = true;   // not connected or refused: the remaining trades keep the estimate without retrying
            log.warn("OPTION_PRICES Kite option candles unavailable ({}): trades not already stored keep the estimate", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The contract live trading would have bought that day, if it is still listed: the nearest expiry on or after the
     * day. It only counts when no earlier expiry can have lapsed in between, i.e. it expires no later than that day's
     * scheduled expiry (holidays only move an expiry earlier); else the right contract has expired and is gone.
     */
    static Optional<KiteApi.OptionInstrument> contract(List<KiteApi.OptionInstrument> listed, Key k) {
        LocalDate latest = OptionEstimates.expiryFor(k.underlying(), k.day());
        return listed.stream()
                .filter(o -> o.optionType().equals(k.optionType()) && o.strike() == k.strike() && !o.expiry().isBefore(k.day()))
                .min(Comparator.comparing(KiteApi.OptionInstrument::expiry))
                .filter(o -> !o.expiry().isAfter(latest) && o.instrumentToken() > 0);
    }

}
