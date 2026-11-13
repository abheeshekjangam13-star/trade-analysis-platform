package com.tradeanalysis.strategy.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Kite Connect v3 over REST (https://kite.trade/docs/connect/v3/). The Spring bean uses the platform session
 * (kite_session, the admin's login: market data, quotes, instruments); {@link #of} makes a client for one user's own
 * Kite account (see {@link KiteAccounts}).
 */
@Component
public class KiteRestApi implements KiteApi {

    private final RestClient http;
    private final Supplier<KiteSession.Credentials> credentials;
    private final ObjectMapper json;

    @Autowired
    public KiteRestApi(RestClient.Builder builder, KiteSession session, ObjectMapper json,
                       @Value("${kite.base-url:https://api.kite.trade}") String baseUrl) {
        this(builder.baseUrl(baseUrl).defaultHeader("X-Kite-Version", "3").build(), session::require, json);
    }

    private KiteRestApi(RestClient http, Supplier<KiteSession.Credentials> credentials, ObjectMapper json) {
        this.http = http; this.credentials = credentials; this.json = json;
    }

    /** A client signed with these credentials (one user's own Kite account); http must have the Kite base URL. */
    public static KiteApi of(RestClient http, KiteSession.Credentials creds, ObjectMapper json) {
        return new KiteRestApi(http, () -> creds, json);
    }

    @Override public Profile profile() {
        Map<String, Object> d = map(call(c -> c.get().uri("/user/profile")));
        return new Profile(str(d.get("user_id")), str(d.get("user_name")), str(d.get("broker")));
    }

    @Override public Margins margins() {
        Map<String, Object> eq = map(map(call(c -> c.get().uri("/user/margins"))).get("equity"));
        Map<String, Object> available = map(eq.get("available"));
        Map<String, Object> utilised = map(eq.get("utilised"));
        double cash = num(available.getOrDefault("live_balance", available.get("cash")));
        return new Margins(cash, num(eq.get("net")), num(utilised.get("debits")));
    }

    @Override public List<BrokerPosition> positions() {
        List<BrokerPosition> out = new ArrayList<>();
        for (Object o : list(map(call(c -> c.get().uri("/portfolio/positions"))).get("net"))) {
            Map<String, Object> p = map(o);
            out.add(new BrokerPosition(str(p.get("tradingsymbol")), str(p.get("exchange")), str(p.get("product")),
                    (int) num(p.get("quantity")), num(p.get("average_price")), num(p.get("last_price")), num(p.get("pnl"))));
        }
        return out;
    }

    @Override public List<BrokerOrder> orders() {
        return list(call(c -> c.get().uri("/orders"))).stream().map(o -> order(map(o))).toList();
    }

    @Override public BrokerOrder order(String orderId) {
        List<Object> history = list(call(c -> c.get().uri("/orders/{id}", orderId)));
        if (history.isEmpty()) throw new TradingException("Kite has no history for order " + orderId);
        return order(map(history.get(history.size() - 1)));
    }

    @Override public String placeOrder(PlaceOrder o) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("exchange", o.exchange());
        form.add("tradingsymbol", o.tradingsymbol());
        form.add("transaction_type", o.transactionType());
        form.add("quantity", String.valueOf(o.quantity()));
        form.add("product", o.product());
        form.add("order_type", o.orderType());
        form.add("validity", "DAY");
        if (o.price() != null) form.add("price", String.valueOf(o.price()));
        if (o.triggerPrice() != null) form.add("trigger_price", String.valueOf(o.triggerPrice()));
        if ("MARKET".equals(o.orderType()) || "SL-M".equals(o.orderType())) form.add("market_protection", "-1");
        if (o.tag() != null) form.add("tag", o.tag());
        Map<String, Object> d = map(call(c -> c.post().uri("/orders/regular")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)));
        return str(d.get("order_id"));
    }

    @Override public void cancelOrder(String orderId) {
        call(c -> c.delete().uri("/orders/regular/{id}", orderId));
    }

    @Override public Map<String, Double> ltp(List<String> instruments) {
        if (instruments.isEmpty()) return Map.of();
        Map<String, Object> d = map(call(c -> c.get().uri(u -> {
            u.path("/quote/ltp");
            instruments.forEach(i -> u.queryParam("i", i));
            return u.build();
        })));
        Map<String, Double> out = new LinkedHashMap<>();
        d.forEach((k, v) -> out.put(k, num(map(v).get("last_price"))));
        return out;
    }

    @Override public List<OptionInstrument> optionsOf(com.tradeanalysis.common.market.Underlying underlying) {
        KiteSession.Credentials creds = credentials.get();
        String csv;
        try {
            csv = http.get().uri("/instruments/NFO").header("Authorization", creds.authHeader()).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new TradingException(kiteMessage(e));
        }
        return parseOptions(csv, underlying.optionName);
    }

    /** Kite instrument tokens of the indices strategies trade (NSE, fixed). */
    static final Map<String, Long> INDEX_TOKENS = Map.of("NIFTY 50", 256265L, "NIFTY BANK", 260105L);
    private static final java.time.format.DateTimeFormatter KITE_TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Kolkata"));

    @Override public List<MinuteBar> minuteBars(String indexSymbol, java.time.Instant from, java.time.Instant to) {
        Long token = INDEX_TOKENS.get(indexSymbol.toUpperCase(java.util.Locale.ROOT));
        if (token == null) return List.of();
        Object data = call(c -> c.get().uri("/instruments/historical/{token}/minute?from={f}&to={t}",
                token, KITE_TIME.format(from), KITE_TIME.format(to)));
        return parseMinuteBars(map(data).get("candles"));
    }

    /** Kite candles: [timestamp, open, high, low, close, volume], timestamp like 2026-11-13T10:58:00+0530. */
    static List<MinuteBar> parseMinuteBars(Object candles) {
        List<MinuteBar> out = new ArrayList<>();
        java.time.format.DateTimeFormatter ts = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
        for (Object row : list(candles)) {
            List<Object> r = list(row);
            if (r.size() < 4) continue;
            out.add(new MinuteBar(java.time.OffsetDateTime.parse(r.get(0).toString(), ts).toInstant(), num(r.get(2)), num(r.get(3))));
        }
        return out;
    }

    /**
     * Options of one index from the NFO list, by its option name ("NIFTY", "BANKNIFTY").
     * Columns: instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
     */
    static List<OptionInstrument> parseOptions(String csv, String optionName) {
        List<OptionInstrument> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        try (BufferedReader r = new BufferedReader(new StringReader(csv))) {
            String[] header = r.readLine().split(",", -1);
            Map<String, Integer> col = new HashMap<>();
            for (int i = 0; i < header.length; i++) col.put(header[i].trim(), i);
            String line;
            while ((line = r.readLine()) != null) {
                String[] c = line.split(",", -1);
                if (c.length < header.length) continue;
                String type = c[col.get("instrument_type")].trim();
                if (!optionName.equals(unquote(c[col.get("name")])) || !(type.equals("CE") || type.equals("PE"))) continue;
                out.add(new OptionInstrument(c[col.get("tradingsymbol")].trim(), LocalDate.parse(c[col.get("expiry")].trim()),
                        (int) Math.round(Double.parseDouble(c[col.get("strike")])), type,
                        Integer.parseInt(c[col.get("lot_size")].trim()), Double.parseDouble(c[col.get("tick_size")])));
            }
        } catch (java.io.IOException e) {
            throw new TradingException("Could not read the Kite NFO instruments list");
        }
        return out;
    }

    private static String unquote(String s) { return s.trim().replace("\"", ""); }

    private Object call(Function<RestClient, RestClient.RequestHeadersSpec<?>> request) {
        KiteSession.Credentials creds = credentials.get();
        try {
            Map<?, ?> body = request.apply(http).header("Authorization", creds.authHeader()).retrieve().body(Map.class);
            return body == null ? null : body.get("data");
        } catch (RestClientResponseException e) {
            throw new TradingException(kiteMessage(e));
        }
    }

    private String kiteMessage(RestClientResponseException e) {
        try {
            Map<?, ?> body = json.readValue(e.getResponseBodyAsString(), Map.class);
            Object msg = body.get("message");
            if (msg != null) return "Kite: " + msg;
        } catch (Exception ignored) { }
        return e.getStatusCode().value() == 403 ? "Kite session expired: log in to Kite again" : "Kite error " + e.getStatusCode().value();
    }

    private static BrokerOrder order(Map<String, Object> o) {
        return new BrokerOrder(str(o.get("order_id")), str(o.get("status")), str(o.get("tradingsymbol")), str(o.get("exchange")),
                str(o.get("transaction_type")), (int) num(o.get("quantity")), (int) num(o.get("filled_quantity")),
                num(o.get("average_price")), o.get("price") == null ? null : num(o.get("price")), str(o.get("order_type")),
                str(o.get("product")), str(o.get("status_message")), str(o.get("tag")), str(o.get("order_timestamp")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : o == null ? 0 : Double.parseDouble(o.toString()); }
}
