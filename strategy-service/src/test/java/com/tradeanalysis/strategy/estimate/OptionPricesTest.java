package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.market.Underlying;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.trading.KiteApi;
import com.tradeanalysis.strategy.trading.NiftyOptions;
import com.tradeanalysis.strategy.trading.TradingException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Backtest trades priced with the real premiums of the option live trading would have bought (19 Nov 2026's trades). */
class OptionPricesTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 11, 19);
    /** NIFTY's November monthly expiry: Tuesday 24 Nov, moved to Monday 23 Nov by the holiday. */
    static final LocalDate EXPIRY = LocalDate.of(2026, 11, 23);
    static final KiteApi.OptionInstrument PE = new KiteApi.OptionInstrument("NIFTY26NOV25150PE", EXPIRY, 25150, "PE", 65, 0.05, 111L);
    static final KiteApi.OptionInstrument CE = new KiteApi.OptionInstrument("NIFTY26NOV25350CE", EXPIRY, 25350, "CE", 65, 0.05, 222L);

    static Instant at(int h, int m) { return DAY.atTime(h, m).atZone(IST).toInstant(); }

    /** A 4-lot NIFTY trade (260 units), no charges, stamped with its 5-minute signal candle's start. */
    static NiftyTradeLog trade(String side, int strike, Instant signalCandle, double entrySpot) {
        NiftyTradeLog t = new NiftyTradeLog(7L, "NIFTY 50", DAY, 1, side.equals("CE") ? "BULLISH" : "BEARISH", 0, 0,
                at(9, 15), signalCandle, entrySpot, side, strike, 4, 65, side.equals("CE") ? entrySpot - 100 : entrySpot + 50);
        t.setContext(0.0, 0);
        return t;
    }

    /** The narrow-CPR PE trade: signal candle 09:25, stop hit in the 09:35 minute. */
    static NiftyTradeLog cprTrade() {
        NiftyTradeLog t = trade("PE", 25150, at(9, 25), 25115.90);
        t.close(at(9, 35), 25165.90, "SL_HIT", 25165.90);
        return t;
    }

    /** The prev-day CE trade: signal candle 13:45; on index prices alone it reaches its target at 14:23. */
    static NiftyTradeLog ceTrade() {
        NiftyTradeLog t = trade("CE", 25350, at(13, 45), 25395.35);
        t.close(at(14, 23), 25456.05, "TARGET_HIT", 25295.35);
        return t;
    }

    /** One close per minute from 09:15 to 15:29: {@code base}, with the given minutes' closes. */
    static NavigableMap<Instant, Double> closes(double base, Map<Instant, Double> set) {
        NavigableMap<Instant, Double> m = new TreeMap<>();
        for (Instant t = at(9, 15); t.isBefore(at(15, 30)); t = t.plusSeconds(60)) m.put(t, set.getOrDefault(t, base));
        return m;
    }

    static NavigableMap<Instant, Double> pePremiums() { return closes(160, Map.of(at(9, 29), 179.99, at(9, 35), 145.65)); }

    static NavigableMap<Instant, Double> cePremiums() {
        Map<Instant, Double> set = new HashMap<>();
        set.put(at(13, 49), 171.00);
        set.put(at(13, 50), 165.15);
        for (Instant t = at(13, 51); t.isBefore(at(15, 30)); t = t.plusSeconds(60)) set.put(t, 200.0);
        return closes(150, set);
    }

    @Test
    void entryIsPricedAsTheSignalCandleClosesAndExitAsTheExitMinuteEnds() {
        NiftyTradeLog t = cprTrade();
        t.priceWith(PE.tradingsymbol(), pePremiums(), OptionPrices.enteredAt(t));

        assertThat(OptionPrices.enteredAt(t)).isEqualTo(at(9, 30));
        assertThat(t.getOptionSymbol()).isEqualTo("NIFTY26NOV25150PE");
        assertThat(t.getEntryPremium()).isEqualTo(179.99);   // the 09:29 minute's close: the price at 09:30
        assertThat(t.getExitPremium()).isEqualTo(145.65);    // the 09:35 minute's close: the price at 09:36
        assertThat(t.hasRealPrices()).isTrue();
        assertThat(t.getEstimatedPnl()).isCloseTo((145.65 - 179.99) * 260, within(0.01));
    }

    @Test
    void withoutAPremiumAtTheEntryTheTradeKeepsTheEstimate() {
        NiftyTradeLog t = cprTrade();
        double estimate = t.getEstimatedPnl();
        t.priceWith(PE.tradingsymbol(), new TreeMap<>(Map.of(at(14, 0), 120.0)), OptionPrices.enteredAt(t));

        assertThat(t.getEntryPremium()).isNull();
        assertThat(t.getOptionSymbol()).isNull();
        assertThat(t.getEstimatedPnl()).isEqualTo(estimate);
        assertThat(estimate).isCloseTo(-50 * 260 * 0.5, within(0.01));
    }

    @Test
    void theMaxDayLossFiresOnRealPricesAsLiveTradingDid() {
        NiftyTradeLog cpr = cprTrade(), ce = ceTrade();
        cpr.priceWith(PE.tradingsymbol(), pePremiums(), OptionPrices.enteredAt(cpr));
        ce.priceWith(CE.tradingsymbol(), cePremiums(), OptionPrices.enteredAt(ce));

        DayLossStop.Result r = DayLossStop.apply(List.of(cpr, ce), 10_000.0, Map.of("NIFTY 50", nifty19Nov()));

        // −8,928 closed in the morning; the CE trade at 165.15 at 13:51 adds −1,521: past −10,000, closed at 13:51.
        assertThat(r.stops()).hasSize(1);
        assertThat(r.stops().get(0).at()).isEqualTo(at(13, 51));
        assertThat(ce.getExitReason()).isEqualTo(DayLossStop.REASON);
        assertThat(ce.getExitTimestamp()).isEqualTo(at(13, 51));
        assertThat(ce.getExitPremium()).isEqualTo(165.15);
        assertThat(ce.getEstimatedPnl()).isCloseTo((165.15 - 171.00) * 260, within(0.01));
        assertThat(cpr.getExitReason()).isEqualTo("SL_HIT");
    }

    @Test
    void onTheEstimateTheSameDayNeverReachesTheLimit() {
        NiftyTradeLog cpr = cprTrade(), ce = ceTrade();
        DayLossStop.Result r = DayLossStop.apply(List.of(cpr, ce), 10_000.0, Map.of("NIFTY 50", nifty19Nov()));
        assertThat(r.stops()).isEmpty();
        assertThat(ce.getExitReason()).isEqualTo("TARGET_HIT");
    }

    /** NIFTY at 25,115.90 until 13:48, 25,395.35 in the 13:49 minute (the CE entry), then 25,400. */
    static PriceSeries nifty19Nov() {
        List<Candle> out = new ArrayList<>();
        for (Instant t = at(9, 15); t.isBefore(at(15, 30)); t = t.plusSeconds(60)) {
            BigDecimal p = BigDecimal.valueOf(t.isBefore(at(13, 49)) ? 25115.90 : t.equals(at(13, 49)) ? 25395.35 : 25400);
            out.add(new Candle(t, p, p, p, p, 0));
        }
        return new PriceSeries("NIFTY 50", Interval.MINUTE_1, out);
    }

    // ── Which contract ───────────────────────────────────────────────────────

    static OptionPrices.Key key(String side, int strike, LocalDate day) { return new OptionPrices.Key(Underlying.NIFTY, side, strike, day); }

    @Test
    void theContractIsTheNearestListedExpiryWhenNoEarlierOneCanHaveLapsed() {
        KiteApi.OptionInstrument later = new KiteApi.OptionInstrument("NIFTY26D0125150PE", EXPIRY.plusDays(8), 25150, "PE", 65, 0.05, 333L);
        assertThat(OptionPrices.contract(List.of(later, PE, CE), key("PE", 25150, DAY))).contains(PE);
        // On 10 Nov the nearest expiry was 10 Nov itself, now gone: the listed 23 Nov contract is not what was bought.
        assertThat(OptionPrices.contract(List.of(later, PE, CE), key("PE", 25150, LocalDate.of(2026, 11, 10)))).isEmpty();
        // No instrument token: its candles can't be asked for.
        KiteApi.OptionInstrument noToken = new KiteApi.OptionInstrument("NIFTY26NOV25150PE", EXPIRY, 25150, "PE", 65, 0.05);
        assertThat(OptionPrices.contract(List.of(noToken), key("PE", 25150, DAY))).isEmpty();
    }

    // ── Where the candles come from ──────────────────────────────────────────

    static List<KiteApi.Ohlc> ohlc(NavigableMap<Instant, Double> closes) {
        return closes.entrySet().stream().map(e -> new KiteApi.Ohlc(e.getKey(), e.getValue(), e.getValue(), e.getValue(), e.getValue())).toList();
    }

    @Test
    void aListedContractIsFetchedFromKiteAndAWholeDayStored() {
        NiftyOptions options = mock(NiftyOptions.class);
        KiteApi kite = mock(KiteApi.class);
        OptionCandleStore store = mock(OptionCandleStore.class);
        when(store.find(any(), any(), anyInt(), any())).thenReturn(Optional.empty());
        when(options.all(Underlying.NIFTY)).thenReturn(List.of(PE, CE));
        when(kite.minuteCandles(eq(111L), any(), any())).thenReturn(ohlc(pePremiums()));
        Clock evening = Clock.fixed(at(18, 0), IST);

        NiftyTradeLog t = cprTrade();
        new OptionPrices(options, kite, store, evening).apply(List.of(t));

        assertThat(t.getEntryPremium()).isEqualTo(179.99);
        assertThat(t.getExitPremium()).isEqualTo(145.65);
        verify(kite).minuteCandles(111L, at(9, 15), at(15, 30));
        verify(store).save(eq(Underlying.NIFTY), eq(PE), eq(DAY), anyList());
    }

    @Test
    void aStoredDayNeedsNoKite() {
        NiftyOptions options = mock(NiftyOptions.class);
        KiteApi kite = mock(KiteApi.class);
        OptionCandleStore store = mock(OptionCandleStore.class);
        when(store.find(Underlying.NIFTY, "PE", 25150, DAY)).thenReturn(Optional.of(new OptionCandleStore.Day(PE.tradingsymbol(), pePremiums())));

        NiftyTradeLog t = cprTrade();
        new OptionPrices(options, kite, store, Clock.fixed(at(18, 0), IST)).apply(List.of(t));

        assertThat(t.getEntryPremium()).isEqualTo(179.99);
        verifyNoInteractions(options, kite);
    }

    @Test
    void todaysCandlesAreUsedButNotStoredBeforeTheClose() {
        NiftyOptions options = mock(NiftyOptions.class);
        KiteApi kite = mock(KiteApi.class);
        OptionCandleStore store = mock(OptionCandleStore.class);
        when(store.find(any(), any(), anyInt(), any())).thenReturn(Optional.empty());
        when(options.all(Underlying.NIFTY)).thenReturn(List.of(PE));
        when(kite.minuteCandles(eq(111L), any(), any())).thenReturn(ohlc(pePremiums().headMap(at(10, 0), false)));

        NiftyTradeLog t = cprTrade();
        new OptionPrices(options, kite, store, Clock.fixed(at(10, 0), IST)).apply(List.of(t));

        assertThat(t.getExitPremium()).isEqualTo(145.65);
        verify(kite).minuteCandles(111L, at(9, 15), at(10, 0));
        verify(store, never()).save(any(), any(), any(), anyList());
    }

    @Test
    void withoutKiteTradesKeepTheEstimateAndKiteIsAskedOnce() {
        NiftyOptions options = mock(NiftyOptions.class);
        OptionCandleStore store = mock(OptionCandleStore.class);
        when(store.find(any(), any(), anyInt(), any())).thenReturn(Optional.empty());
        when(options.all(any())).thenThrow(new TradingException("Kite is not connected: log in to Kite first"));

        NiftyTradeLog cpr = cprTrade(), ce = ceTrade();
        new OptionPrices(options, mock(KiteApi.class), store, Clock.fixed(at(18, 0), IST)).apply(List.of(cpr, ce));

        assertThat(cpr.getEntryPremium()).isNull();
        assertThat(ce.getEntryPremium()).isNull();
        verify(options, times(1)).all(any());
    }

    // ── Slippage allowance ───────────────────────────────────────────────────

    @Test
    void slippageIsPaidOnBothSidesWhileThePremiumsStayTheMarkets() {
        NiftyTradeLog t = cprTrade();
        t.priceWith(PE.tradingsymbol(), pePremiums(), OptionPrices.enteredAt(t));
        t.useSlippage(1.0);

        assertThat(t.getEntryPremium()).isEqualTo(179.99);
        assertThat(t.getExitPremium()).isEqualTo(145.65);
        assertThat(t.getEstimatedPnl()).isCloseTo((145.65 - 179.99) * 260 - 2 * 1.0 * 260, within(0.01));   // −8,928 − 520
        // Valued open, it pays both sides too: what closing then would make.
        assertThat(t.pnlAt(at(9, 34), 0)).isCloseTo((160 - 179.99) * 260 - 520, within(0.01));
    }

    @Test
    void anEstimatedTradePaysTheAllowanceToo() {
        NiftyTradeLog t = cprTrade();
        t.useSlippage(1.0);
        assertThat(t.getEstimatedPnl()).isCloseTo(-50 * 260 * 0.5 - 520, within(0.01));
    }

    @Test
    void theAllowanceIsTheStrategysElseTheStoredOneElseTheIndexDefault() {
        OptionCandleStore store = mock(OptionCandleStore.class);
        when(store.find(any(), any(), anyInt(), any())).thenReturn(Optional.empty());
        OptionPrices prices = new OptionPrices(mock(NiftyOptions.class), mock(KiteApi.class), store, Clock.fixed(at(18, 0), IST));

        NiftyTradeLog nifty = cprTrade();
        NiftyTradeLog bank = new NiftyTradeLog(7L, "NIFTY BANK", DAY, 1, "BULLISH", 0, 0, at(9, 15), at(9, 45), 61_700, "CE", 61_400, 1, 30, 61_600);
        bank.close(at(9, 51), 61_780, "TARGET_HIT", 61_600);
        prices.apply(List.of(nifty, bank));
        assertThat(nifty.getSlippage()).isEqualTo(1.0);
        assertThat(bank.getSlippage()).isEqualTo(5.0);
        assertThat(bank.getEstimatedPnl()).isCloseTo(80 * 30 * 0.5 - 2 * 5.0 * 30, within(0.01));

        prices.apply(List.of(nifty), 0.0);     // the strategy's own setting wins: 0 = none
        assertThat(nifty.getSlippage()).isZero();
        nifty.useSlippage(2.5);
        prices.apply(List.of(nifty));          // a stored trade (portfolio report) keeps its allowance
        assertThat(nifty.getSlippage()).isEqualTo(2.5);
    }

    @Test
    void theMaxDayLossCountsTheAllowance() {
        // On real prices the 19 Nov day reaches −10,449 at 13:51; with no allowance a 10,600 limit holds, with 1 pt it doesn't.
        NiftyTradeLog cpr = cprTrade(), ce = ceTrade();
        cpr.priceWith(PE.tradingsymbol(), pePremiums(), OptionPrices.enteredAt(cpr));
        ce.priceWith(CE.tradingsymbol(), cePremiums(), OptionPrices.enteredAt(ce));
        assertThat(DayLossStop.apply(List.of(cpr, ce), 10_600.0, Map.of("NIFTY 50", nifty19Nov())).stops()).isEmpty();

        NiftyTradeLog cpr2 = cprTrade(), ce2 = ceTrade();
        for (NiftyTradeLog t : List.of(cpr2, ce2)) t.useSlippage(1.0);
        cpr2.priceWith(PE.tradingsymbol(), pePremiums(), OptionPrices.enteredAt(cpr2));
        ce2.priceWith(CE.tradingsymbol(), cePremiums(), OptionPrices.enteredAt(ce2));
        DayLossStop.Result r = DayLossStop.apply(List.of(cpr2, ce2), 10_600.0, Map.of("NIFTY 50", nifty19Nov()));
        assertThat(r.stops()).hasSize(1);
        assertThat(ce2.getExitReason()).isEqualTo(DayLossStop.REASON);
    }
}
