package com.tradeanalysis.common.indicator;

import java.util.Map;

/** Typed access to loosely-typed request parameters. */
public final class Params {

    private final Map<String, Object> values;

    public Params(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    public int intOr(String key, int fallback) {
        Object v = values.get(key);
        if (v == null) return fallback;
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }

    public double doubleOr(String key, double fallback) {
        Object v = values.get(key);
        if (v == null) return fallback;
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
    }

    public String stringOr(String key, String fallback) {
        Object v = values.get(key);
        return v == null ? fallback : v.toString();
    }

    public Map<String, Object> asMap() {
        return values;
    }
}
