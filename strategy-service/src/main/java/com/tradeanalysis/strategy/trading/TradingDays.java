package com.tradeanalysis.strategy.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whether NSE trades on a day: weekdays that are not on the holiday list (trading.market-holidays, from NSE's yearly
 * circular). Used where we must know before the first trade, e.g. the 09:15 market-open alert.
 */
@Component
public class TradingDays {

    private static final Logger log = LoggerFactory.getLogger(TradingDays.class);

    private final Set<LocalDate> holidays;

    public TradingDays(@Value("${trading.market-holidays:}") String list) {
        TreeSet<LocalDate> set = parse(list);
        this.holidays = Collections.unmodifiableSet(set);
        log.info("TRADING_DAYS {} NSE holidays configured ({} to {})", set.size(),
                set.isEmpty() ? "-" : set.first(), set.isEmpty() ? "-" : set.last());
    }

    static TreeSet<LocalDate> parse(String list) {
        TreeSet<LocalDate> out = new TreeSet<>();
        if (list == null) return out;
        Arrays.stream(list.split("[,\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).forEach(s -> {
            try { out.add(LocalDate.parse(s)); }
            catch (DateTimeParseException e) { log.warn("TRADING_DAYS ignoring '{}' in trading.market-holidays (use yyyy-MM-dd)", s); }
        });
        return out;
    }

    public boolean isHoliday(LocalDate d) { return holidays.contains(d); }

    /** A weekday that is not an NSE holiday. */
    public boolean isTradingDay(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    /** Whether the list has any date in this year (if not, someone forgot to add the new year's circular). */
    public boolean coversYear(int year) { return holidays.stream().anyMatch(d -> d.getYear() == year); }
}
