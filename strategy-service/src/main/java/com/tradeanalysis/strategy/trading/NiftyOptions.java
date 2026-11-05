package com.tradeanalysis.strategy.trading;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** NIFTY option contracts from Kite, cached for the day; resolves the contract a strategy trade needs. */
@Component
public class NiftyOptions {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final KiteApi kite;
    private final Clock clock;
    private volatile List<KiteApi.OptionInstrument> cache = List.of();
    private volatile LocalDate loadedOn;

    public NiftyOptions(KiteApi kite, Clock clock) { this.kite = kite; this.clock = clock; }

    public synchronized List<KiteApi.OptionInstrument> all() {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        if (!today.equals(loadedOn) || cache.isEmpty()) {
            cache = kite.niftyOptions();
            loadedOn = today;
        }
        return cache;
    }

    /** Expiries from today on, nearest first. */
    public List<LocalDate> expiries() {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        return all().stream().map(KiteApi.OptionInstrument::expiry).filter(e -> !e.isBefore(today)).distinct().sorted().toList();
    }

    /** All strikes of one expiry (CE and PE), by strike. */
    public List<KiteApi.OptionInstrument> chain(LocalDate expiry) {
        return all().stream().filter(o -> o.expiry().equals(expiry))
                .sorted(Comparator.comparingInt(KiteApi.OptionInstrument::strike).thenComparing(KiteApi.OptionInstrument::optionType)).toList();
    }

    /** The nearest-expiry contract (expiring today or later) for this strike and type. */
    public KiteApi.OptionInstrument resolve(String optionType, int strike, LocalDate onOrAfter) {
        return all().stream()
                .filter(o -> o.optionType().equals(optionType) && o.strike() == strike && !o.expiry().isBefore(onOrAfter))
                .min(Comparator.comparing(KiteApi.OptionInstrument::expiry))
                .orElseThrow(() -> new TradingException("No NIFTY " + strike + " " + optionType + " contract listed from " + onOrAfter));
    }

    public Optional<KiteApi.OptionInstrument> find(String tradingsymbol) {
        return all().stream().filter(o -> o.tradingsymbol().equals(tradingsymbol)).findFirst();
    }
}
