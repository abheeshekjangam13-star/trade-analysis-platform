package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.market.Underlying;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Option contracts of the indices strategies trade (NIFTY, BANKNIFTY) from Kite, cached per index for the day;
 * resolves the contract a strategy trade needs.
 */
@Component
public class NiftyOptions {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final KiteApi kite;
    private final Clock clock;
    private final Map<Underlying, List<KiteApi.OptionInstrument>> cache = new EnumMap<>(Underlying.class);
    private final Map<Underlying, LocalDate> loadedOn = new EnumMap<>(Underlying.class);

    public NiftyOptions(KiteApi kite, Clock clock) { this.kite = kite; this.clock = clock; }

    public synchronized List<KiteApi.OptionInstrument> all(Underlying u) {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        List<KiteApi.OptionInstrument> list = cache.get(u);
        if (list == null || list.isEmpty() || !today.equals(loadedOn.get(u))) {
            list = kite.optionsOf(u);
            cache.put(u, list);
            loadedOn.put(u, today);
        }
        return list;
    }

    /** Expiries from today on, nearest first. */
    public List<LocalDate> expiries(Underlying u) {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        return all(u).stream().map(KiteApi.OptionInstrument::expiry).filter(e -> !e.isBefore(today)).distinct().sorted().toList();
    }

    /** All strikes of one expiry (CE and PE), by strike. */
    public List<KiteApi.OptionInstrument> chain(Underlying u, LocalDate expiry) {
        return all(u).stream().filter(o -> o.expiry().equals(expiry))
                .sorted(Comparator.comparingInt(KiteApi.OptionInstrument::strike).thenComparing(KiteApi.OptionInstrument::optionType)).toList();
    }

    /** The nearest-expiry contract (expiring today or later) for this index, strike and type. */
    public KiteApi.OptionInstrument resolve(Underlying u, String optionType, int strike, LocalDate onOrAfter) {
        return all(u).stream()
                .filter(o -> o.optionType().equals(optionType) && o.strike() == strike && !o.expiry().isBefore(onOrAfter))
                .min(Comparator.comparing(KiteApi.OptionInstrument::expiry))
                .orElseThrow(() -> new TradingException("No " + u.optionName + " " + strike + " " + optionType + " contract listed from " + onOrAfter));
    }

    public Optional<KiteApi.OptionInstrument> find(Underlying u, String tradingsymbol) {
        return all(u).stream().filter(o -> o.tradingsymbol().equals(tradingsymbol)).findFirst();
    }
}
