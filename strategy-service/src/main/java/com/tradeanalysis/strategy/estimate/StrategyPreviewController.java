package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.engine.MarketDataClient;
import com.tradeanalysis.strategy.model.Strategy;
import com.tradeanalysis.strategy.service.StrategyService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

import static com.tradeanalysis.strategy.estimate.OptionEstimates.*;

/**
 * "If this strategy trades": at today's NIFTY level and India VIX, the option each direction would buy, the capital it
 * uses (premium × quantity, also as a share of the capital given), the profit if the target is hit and the most it loses
 * if the stop is hit. Option prices are Black-Scholes estimates (see OptionEstimates).
 */
@RestController
public class StrategyPreviewController {

    public record Leg(String optionType, int strike, double premium, int quantity, double capitalUsed, Double capitalUsedPct,
                      Double targetPoints, Double profitAtTarget, Double stopPoints, String stopBasis, Double lossAtStop,
                      Double riskReward) {}

    public record Preview(Long strategyId, double capital, double spot, Instant spotAt, double iv, boolean ivAssumed,
                          LocalDate expiry, double daysToExpiry, double costPerTrade, List<Leg> legs, List<String> notes,
                          Double maxLossAmount, Double stopPointsForAmount,
                          int lots, int lotSize, int maxLotsForCapital, double capitalPerLot) {}

    private final StrategyService strategies;
    private final MarketDataClient marketData;
    private final Clock clock;

    public StrategyPreviewController(StrategyService strategies, MarketDataClient marketData, Clock clock) {
        this.strategies = strategies; this.marketData = marketData; this.clock = clock;
    }

    @GetMapping("/api/strategies/{id}/preview")
    @Operation(summary = "Estimated capital used, profit at target and loss at stop if this NIFTY strategy trades now "
            + "(maxStopPoints or maxLossAmount in ₹: try a Max SL without saving it)")
    public Preview preview(@PathVariable Long id, @RequestParam(defaultValue = "50000") double capital,
                           @RequestParam(required = false) Double maxStopPoints,
                           @RequestParam(required = false) Double maxLossAmount) {
        Strategy s = strategies.get(id);   // the caller's own (admins: any)
        if (!"NIFTY_OPENING_BREAKOUT".equals(s.getType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The preview is for NIFTY opening-breakout strategies");
        if (capital <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Capital must be more than 0");
        Map<String, String> p = s.getParameters() == null ? Map.of() : s.getParameters();
        int lots = intOr(p, "lots", 2), lotSize = intOr(p, "lotSize", 65), qty = lots * lotSize;
        double target = numOr(p, "targetPoints", 0), cost = numOr(p, "costPerTrade", 0);
        // A Max SL to try before saving it to the strategy (0 = none), else the strategy's own
        if (maxStopPoints != null && (maxStopPoints < 0 || maxStopPoints > 500))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Max SL must be between 0 and 500 points");
        if (maxLossAmount != null && (maxLossAmount <= 0 || maxLossAmount > 10_000_000))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Max loss must be more than ₹0");
        double maxStop = maxStopPoints != null ? maxStopPoints : numOr(p, "maxStopPoints", 0);
        String directions = p.getOrDefault("directions", "BOTH");
        List<String> types = directions.equals("BOTH") ? List.of("CE", "PE") : List.of(directions);

        Instant now = clock.instant();
        Candle last = latest("NIFTY 50");
        double spot = last.close().doubleValue();
        Candle vixNow = latestOrNull(VIX);
        boolean ivAssumed = vixNow == null;
        double iv = ivAssumed ? DEFAULT_IV : vixNow.close().doubleValue() / 100;
        LocalDate today = now.atZone(IST).toLocalDate();
        LocalDate expiry = weeklyExpiry(today);
        if (!now.isBefore(expiry.atTime(15, 30).atZone(IST).toInstant())) expiry = weeklyExpiry(today.plusDays(1));
        double years = yearsToExpiry(now, expiry);

        // A Max SL given in ₹: the NIFTY points at which the option loses that much (with charges) today; with CE and PE,
        // the tighter of the two, so neither loses more. Null when even the whole premium is less than the amount.
        Double pointsForAmount = null;
        String amountNote = null;
        if (maxLossAmount != null) {
            if (maxLossAmount <= cost)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Max loss must be more than the charges of ₹" + trim(cost));
            for (String type : types) {
                Double pts = pointsForLoss(type.equals("CE"), spot, years, iv, qty, cost, maxLossAmount);
                if (pts != null) pointsForAmount = pointsForAmount == null ? pts : Math.min(pointsForAmount, pts);
            }
            if (pointsForAmount != null) {
                maxStop = pointsForAmount;
                amountNote = "A max loss of ₹" + trim(maxLossAmount) + " is about " + trim(pointsForAmount) + " NIFTY points at today's prices"
                        + (types.size() > 1 ? " (the tighter of CE and PE)" : "") + ". The stop is kept in points, so the rupee loss "
                        + "at it moves a little as option prices change.";
            } else {
                maxStop = 0;
                amountNote = "₹" + trim(maxLossAmount) + " is more than the whole premium of one trade, so no stop is needed to keep "
                        + "within it: the most a trade can lose is the premium paid.";
            }
        }

        // A maximum loss only with a fixed stop (Max SL); otherwise the stop comes from each day's first candle.
        Double stop = maxStop > 0 ? maxStop : null;
        String basis = maxStop > 0 ? (pointsForAmount != null ? "Max loss ₹" + trim(maxLossAmount) + " ≈ " + trim(maxStop) + " points"
                : "Max SL of " + trim(maxStop) + " points")
                : "No fixed stop-loss is set, so the maximum loss isn't known in advance: the stop is placed at the first "
                  + "5-minute candle each day and can be far away. Set \"Max SL (points)\" on this strategy to cap and see it.";

        List<Leg> legs = new ArrayList<>();
        for (String type : types) {
            boolean call = type.equals("CE");
            int k = strike(call, spot);
            double premium = price(call, spot, k, years, iv), used = premium * qty;
            Double profit = target > 0 ? round((price(call, spot + (call ? target : -target), k, years, iv) - premium) * qty - cost) : null;
            Double loss = stop == null ? null
                    : round(Math.min(used, (premium - price(call, spot - (call ? stop : -stop), k, years, iv)) * qty) + cost);
            legs.add(new Leg(type, k, round2(premium), qty, round(used), Math.round(used / capital * 1000) / 10.0,
                    target > 0 ? target : null, profit, stop, basis, loss,
                    profit != null && loss != null && loss > 0 ? Math.round(profit / loss * 100) / 100.0 : null));
        }

        // Lots the capital pays for: one position at a time (CE or PE), so the dearer option sets the limit
        double perLot = 0;
        for (Leg l : legs) perLot = Math.max(perLot, l.premium() * lotSize);
        int maxLots = perLot > 0 ? (int) Math.max(0, Math.floor((capital - cost) / perLot)) : 0;

        List<String> notes = new ArrayList<>();
        if (maxLots < lots)
            notes.add(maxLots == 0
                    ? "₹" + trim(capital) + " doesn't cover even 1 lot (about ₹" + trim(round(perLot)) + " today)."
                    : "This strategy trades " + lots + " lots, but ₹" + trim(capital) + " covers only " + maxLots + " at today's premium.");
        if (amountNote != null) notes.add(amountNote);
        notes.add("Estimates at NIFTY " + trim(round2(spot)) + " and " + (ivAssumed ? "an assumed " : "India VIX ")
                + trim(round2(iv * 100)) + "% volatility, " + expiry + " expiry. Real premiums differ with spreads and skew.");
        notes.add("The option moves less than NIFTY, so a " + (target > 0 ? trim(target) + "-point target" : "target")
                + " earns less than " + (target > 0 ? trim(target) + " × " + qty : "points × quantity") + ".");
        notes.add("A gap or a fast market can fill beyond the stop, so the loss can be larger; it can never exceed the capital used plus charges.");
        if (target <= 0) notes.add("No target set: the trade runs until the stop or the 15:20 square-off.");
        return new Preview(id, capital, round2(spot), last.timestamp(), Math.round(iv * 1000) / 1000.0, ivAssumed, expiry,
                Math.round(years * 365 * 10) / 10.0, cost, legs, notes, maxLossAmount, pointsForAmount,
                lots, lotSize, maxLots, round(perLot));
    }

    /**
     * NIFTY points against the trade at which the option loses {@code amount} (charges included), rounded down to half a
     * point so the loss stays within it; null when even losing the whole premium costs less than the amount.
     */
    static Double pointsForLoss(boolean call, double spot, double years, double iv, int qty, double cost, double amount) {
        int k = strike(call, spot);
        double premium = price(call, spot, k, years, iv);
        if (premium * qty + cost <= amount) return null;
        double lo = 0, hi = spot;   // the loss grows with the distance, up to the whole premium
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            double loss = (premium - price(call, call ? spot - mid : spot + mid, k, years, iv)) * qty + cost;
            if (loss > amount) hi = mid; else lo = mid;
        }
        return Math.max(0.5, Math.floor(lo * 2) / 2);
    }

    private Candle latest(String symbol) {
        Candle c = latestOrNull(symbol);
        if (c == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "No NIFTY price available right now (market data offline?)");
        return c;
    }

    private Candle latestOrNull(String symbol) {
        for (Interval iv : List.of(Interval.MINUTE_5, Interval.DAY_1)) {
            try {
                PriceSeries s = marketData.getSeries(symbol, iv, 1);
                if (s != null && !s.candles().isEmpty()) return s.candles().get(s.candles().size() - 1);
            } catch (RuntimeException ignored) { /* try the next interval */ }
        }
        return null;
    }

    private static int intOr(Map<String, String> p, String k, int d) {
        try { return p.containsKey(k) && !p.get(k).isBlank() ? Integer.parseInt(p.get(k).trim()) : d; } catch (NumberFormatException e) { return d; }
    }
    private static double numOr(Map<String, String> p, String k, double d) {
        try { return p.containsKey(k) && !p.get(k).isBlank() ? Double.parseDouble(p.get(k).trim()) : d; } catch (NumberFormatException e) { return d; }
    }
    private static double round(double v) { return Math.round(v); }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
    private static String trim(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }
}
