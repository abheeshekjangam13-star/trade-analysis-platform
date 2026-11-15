package com.tradeanalysis.strategy.user;

import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Each user's permanent client ID, like a broker's client code: "SG" + 6 digits (e.g. SG482917). It is worked out from
 * the user's id with a fixed one-to-one shuffle, so it never changes, never repeats (for the first million users) and
 * does not reveal sign-up order at a glance. Users can log in with it instead of their username.
 */
public final class ClientIds {

    private static final long M = 1_000_000L;
    private static final long A = 7_919L;        // coprime with 10^6, so n -> (A·n + B) mod 10^6 is one-to-one
    private static final long B = 104_729L;
    private static final long A_INV = BigInteger.valueOf(A).modInverse(BigInteger.valueOf(M)).longValueExact();
    private static final Pattern CODE = Pattern.compile("(?i)SG(\\d{6})");

    private ClientIds() {}

    public static String code(long userId) {
        if (userId <= 0 || userId >= M) throw new IllegalArgumentException("No client ID for user id " + userId);
        return "SG" + String.format("%06d", Math.floorMod(A * userId + B, M));
    }

    /** The user id behind a client ID, or empty when the text is not one. */
    public static Optional<Long> userId(String code) {
        if (code == null) return Optional.empty();
        Matcher m = CODE.matcher(code.trim());
        if (!m.matches()) return Optional.empty();
        long n = Long.parseLong(m.group(1));
        long id = Math.floorMod(Math.floorMod(n - B, M) * A_INV, M);
        return id == 0 ? Optional.empty() : Optional.of(id);
    }
}
