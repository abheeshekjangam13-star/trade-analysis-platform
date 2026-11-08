package com.tradeanalysis.common.market;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Exchange sessions outside the usual Monday–Friday 09:15–15:30 IST, such as Diwali Muhurat trading (often on a
 * weekend). During one of these windows the live runner trades and market data is topped up as in market hours,
 * and the app shows a banner all day. Add a line per session from the NSE circular.
 */
public final class SpecialSessions {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * One session: {@code preOpen}–{@code close} is when the app treats the market as open (pre-open to the F&O close);
     * {@code tradingFrom}–{@code tradingTo} is normal trading, shown to users.
     */
    public record Session(LocalDate date, LocalTime preOpen, LocalTime tradingFrom, LocalTime tradingTo, LocalTime close,
                          String name) {
        public boolean covers(ZonedDateTime t) {
            LocalTime lt = t.toLocalTime();
            return t.toLocalDate().equals(date) && !lt.isBefore(preOpen) && lt.isBefore(close);
        }
    }

    public static final List<Session> SESSIONS = List.of(
            // NSE Muhurat trading, Diwali (Sunday): pre-open 14:05, trading 14:20–15:30, F&O until 15:40.
            new Session(LocalDate.of(2026, 11, 8), LocalTime.of(14, 5), LocalTime.of(14, 20), LocalTime.of(15, 30),
                    LocalTime.of(15, 40), "Muhurat trading"));

    private SpecialSessions() {}

    /** The special session running at {@code t}, if any. */
    public static Optional<Session> at(Instant t) {
        ZonedDateTime z = t.atZone(IST);
        return SESSIONS.stream().filter(s -> s.covers(z)).findFirst();
    }

    /** The special session on {@code day} (IST), if any, whatever the time. */
    public static Optional<Session> on(LocalDate day) {
        return SESSIONS.stream().filter(s -> s.date().equals(day)).findFirst();
    }

    public static boolean open(Instant t) { return at(t).isPresent(); }
}
