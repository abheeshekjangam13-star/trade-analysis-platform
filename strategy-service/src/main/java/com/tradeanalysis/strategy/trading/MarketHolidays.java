package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.engine.MarketDataClient;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The weekdays the market was closed (exchange holidays), for the trades calendar. Worked out from NIFTY 50's daily
 * candles rather than a hand-kept list: a past weekday with no daily candle is a day the exchange didn't trade. Every
 * logged-in user may ask (the candles themselves stay admin-only at the gateway).
 */
@RestController
public class MarketHolidays {

    private static final Logger log = LoggerFactory.getLogger(MarketHolidays.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String INDEX = "NIFTY 50";
    static final int MAX_DAYS = 4 * 366;

    private final MarketDataClient marketData;
    private final Clock clock;

    public MarketHolidays(MarketDataClient marketData, Clock clock) { this.marketData = marketData; this.clock = clock; }

    @GetMapping("/api/trading/market-holidays")
    @Operation(summary = "Weekdays between from and to (yyyy-MM-dd) the market was closed, from NIFTY 50's daily candles; "
            + "only days before today; empty when the candles can't be had")
    public List<LocalDate> between(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from)) throw new TradingException("'to' is before 'from'");
        if (from.plusDays(MAX_DAYS).isBefore(to)) throw new TradingException("Ask for at most four years at a time");
        LocalDate today = LocalDate.now(clock.withZone(IST));
        LocalDate end = to.isBefore(today) ? to : today.minusDays(1);   // today's candle may not be in yet
        if (end.isBefore(from)) return List.of();

        List<Candle> candles;
        try {
            PriceSeries s = marketData.getRange(INDEX, Interval.DAY_1, from.atStartOfDay(IST).toInstant(),
                    end.plusDays(1).atStartOfDay(IST).toInstant().minusSeconds(1));
            candles = s == null || s.candles() == null ? List.of() : s.candles();
        } catch (RuntimeException e) {
            log.warn("MARKET_HOLIDAYS no daily candles for {} to {}: {}", from, end, e.getMessage());
            return List.of();
        }
        if (candles.isEmpty()) return List.of();   // no data says nothing about holidays
        Set<LocalDate> traded = candles.stream().map(c -> c.timestamp().atZone(IST).toLocalDate()).collect(Collectors.toSet());
        List<LocalDate> closed = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1)) {
            DayOfWeek wd = d.getDayOfWeek();
            if (wd != DayOfWeek.SATURDAY && wd != DayOfWeek.SUNDAY && !traded.contains(d)) closed.add(d);
        }
        return closed;
    }
}
