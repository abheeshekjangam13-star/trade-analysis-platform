package com.tradeanalysis.strategy;

/** Test helpers: a valid PAN and a name as on the PAN card, derived from a username (unique per username). */
public final class TestPan {
    private TestPan() {}

    /** A valid individual PAN (4th letter P) made from the username. */
    public static String of(String user) {
        int h = Math.floorMod(user.toLowerCase().hashCode(), 26 * 26 * 10_000);
        return "AB" + (char) ('A' + h % 26) + "P" + (char) ('A' + (h / 26) % 26) + String.format("%04d", h / 676) + "Z";
    }

    /** A name that passes the PAN-name check, made from the username. */
    public static String name(String user) {
        String letters = user.replaceAll("[^A-Za-z]", "");
        return "Test " + (letters.isEmpty() ? "User" : letters) + " Kumar";
    }

    /** JSON fields to put before acceptTerms in a sign-up body: fullName and pan, with a trailing comma. */
    public static String fields(String user) {
        return "\"fullName\":\"" + name(user) + "\",\"pan\":\"" + of(user) + "\",";
    }
}
