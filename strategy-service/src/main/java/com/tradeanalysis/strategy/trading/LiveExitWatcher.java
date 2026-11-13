package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.strategy.engine.GapFillEngine;
import com.tradeanalysis.strategy.engine.NiftyOpeningBreakoutEngine;
import com.tradeanalysis.strategy.model.Strategy;
import com.tradeanalysis.strategy.service.StrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Closes open strategy positions the moment the index touches their stop or target, instead of waiting for the
 * 1-minute candle to close and the next minute run (up to two minutes later). Every second in market hours it reads
 * the live index price (through {@link QuoteCache}: one Kite call a second for the whole account, shared with the
 * screens) and compares it with each open position's
 * <ul>
 *   <li>stop: the one the engine keeps (trailed every minute by {@link LiveStrategyRunner}), and</li>
 *   <li>target: entry ± targetPoints, for the strategies whose target is that (not gap fill, not a targetR target,
 *       which stay with the minute run).</li>
 * </ul>
 * CE exits when the index trades at or below the stop / at or above the target; PE the other way round. The stop is
 * checked first, as in the backtests. A price older than {@link #MAX_QUOTE_AGE} is never acted on.
 */
@Component
@ConditionalOnProperty(name = "trading.runner.enabled", havingValue = "true", matchIfMissing = true)
public class LiveExitWatcher {

    private static final Logger log = LoggerFactory.getLogger(LiveExitWatcher.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(5);

    private final LivePositionRepository positions;
    private final StrategyService strategies;
    private final QuoteCache quotes;
    private final LiveStrategyRunner runner;
    private final Clock clock;
    private volatile String lastProblem;

    public LiveExitWatcher(LivePositionRepository positions, StrategyService strategies, QuoteCache quotes,
                           LiveStrategyRunner runner, Clock clock) {
        this.positions = positions; this.strategies = strategies; this.quotes = quotes; this.runner = runner; this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${trading.exit-watch.interval-ms:1000}", initialDelayString = "${trading.exit-watch.initial-delay-ms:30000}")
    public void tick() {
        if (!LiveStrategyRunner.marketOpen(clock.instant())) return;
        try {
            check();
            lastProblem = null;
        } catch (RuntimeException e) {
            if (!Objects.equals(lastProblem, e.getMessage())) log.warn("EXIT_WATCH_FAILED {}", e.getMessage());   // once, not every second
            lastProblem = e.getMessage();
        }
    }

    /** Checks every open PAPER/LIVE position once against the live index price; returns what it closed. */
    public List<String> check() {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        List<LivePosition> open = new ArrayList<>();
        for (TradingMode mode : List.of(TradingMode.PAPER, TradingMode.LIVE))
            open.addAll(positions.findByTradeDateAndModeAndStatus(today, mode, LivePosition.Status.OPEN));
        if (open.isEmpty()) return List.of();   // nothing to watch: no Kite call

        Map<Long, Params> params = new HashMap<>();
        Map<Long, String> symbol = new HashMap<>();
        for (LivePosition p : open) {
            Params pr = params.computeIfAbsent(p.getStrategyId(), id -> {
                Strategy s = strategies.load(id);
                return new Params(new HashMap<>(s.getParameters() == null ? Map.of() : s.getParameters()));
            });
            symbol.put(p.getId(), "NSE:" + NiftyOpeningBreakoutEngine.underlyingOf(pr).indexSymbol);
        }
        QuoteCache.Quotes q = quotes.get(symbol.values().stream().distinct().sorted().toList());
        Instant now = clock.instant();
        if (q.asOf() == null || q.asOf().isBefore(now.minus(MAX_QUOTE_AGE))) return List.of();   // no fresh price: wait

        List<String> did = new ArrayList<>();
        for (LivePosition p : open) {
            Double spot = q.prices().get(symbol.get(p.getId()));
            if (spot == null) continue;
            String reason = touched(p.getOptionType(), spot, p.getCurrentStop(), target(p, params.get(p.getStrategyId())));
            if (reason == null) continue;
            String r = runner.exitOnTouch(p.getId(), spot, reason);
            if (r != null) {
                log.info("EXIT_WATCH {} at index {}", r, spot);
                did.add(r);
            }
        }
        return did;
    }

    /** "SL_HIT" or "TARGET_HIT" when the index price has reached the stop or the target (stop first), else null. */
    static String touched(String optionType, double spot, Double stop, Double target) {
        boolean call = "CE".equals(optionType);
        if (stop != null && (call ? spot <= stop : spot >= stop)) return "SL_HIT";
        if (target != null && (call ? spot >= target : spot <= target)) return "TARGET_HIT";
        return null;
    }

    /** Entry ± targetPoints for the engines whose target is that; null otherwise (gap fill, targetR, no target). */
    static Double target(LivePosition p, Params params) {
        if (GapFillEngine.handles(params)) return null;
        double tp = params.doubleOr("targetPoints", 0);
        if (tp <= 0) return null;
        return "CE".equals(p.getOptionType()) ? p.getEntrySpot() + tp : p.getEntrySpot() - tp;
    }
}
