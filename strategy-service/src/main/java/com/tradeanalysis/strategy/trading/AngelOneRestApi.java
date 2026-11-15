package com.tradeanalysis.strategy.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;
import java.util.function.Function;

/**
 * One user's own Angel One account over SmartAPI REST (https://smartapi.angelone.in/docs), in the shapes of
 * {@link KiteApi}: everything going in and out is translated to Kite's words (symbols via {@link AngelScripMaster},
 * products MIS/NRML/CNC, order types MARKET/LIMIT/SL/SL-M, statuses COMPLETE/REJECTED/CANCELLED/OPEN…), so orders,
 * positions, exits and the UI work the same for both brokers. Market data (quotes, option lists, candles) stays on
 * the platform Kite session.
 *
 * <p>Order ids: Angel cancels by order id and variety but reads one order by its unique order id, so the id this
 * client hands out is {@code AO:<N|S>:<orderid>:<uniqueorderid>} (N = NORMAL, S = STOPLOSS variety).
 */
public class AngelOneRestApi implements KiteApi {

    /** The user's SmartAPI app key and today's session token (the JWT, without "Bearer "). */
    public record Credentials(String apiKey, String jwtToken, String clientCode) {}

    static final String ID_PREFIX = "AO:";
    private static final String P = "/rest/secure/angelbroking";

    private final RestClient http;
    private final Credentials creds;
    private final AngelScripMaster symbols;
    private final NiftyOptions options;
    private final ObjectMapper json;
    private final String algoIdField;

    /** @param http has the SmartAPI base URL and the X-UserType/X-SourceID/IP/MAC headers */
    public AngelOneRestApi(RestClient http, Credentials creds, AngelScripMaster symbols, NiftyOptions options, ObjectMapper json) {
        this(http, creds, symbols, options, json, null);
    }

    /** @param algoIdField the placeOrder field Angel One takes the exchange Algo ID in (blank = not sent until Angel confirms it) */
    public AngelOneRestApi(RestClient http, Credentials creds, AngelScripMaster symbols, NiftyOptions options, ObjectMapper json, String algoIdField) {
        this.http = http; this.creds = creds; this.symbols = symbols; this.options = options; this.json = json;
        this.algoIdField = algoIdField == null || algoIdField.isBlank() ? null : algoIdField.trim();
    }

    public static boolean isAngelOrderId(String id) { return id != null && id.startsWith(ID_PREFIX); }

    // ---- account ------------------------------------------------------------------------------------------------------

    @Override public Profile profile() {
        Map<String, Object> d = map(get(P + "/user/v1/getProfile"));
        return new Profile(str(d.get("clientcode")), str(d.get("name")), "ANGELONE");
    }

    @Override public Margins margins() {
        Map<String, Object> d = map(get(P + "/user/v1/getRMS"));
        return new Margins(num(d.get("availablecash")), num(d.get("net")), num(d.get("utiliseddebits")));
    }

    @Override public List<BrokerPosition> positions() {
        List<BrokerPosition> out = new ArrayList<>();
        for (Object o : list(get(P + "/order/v1/getPosition"))) {
            Map<String, Object> p = map(o);
            Object avg = p.get("netprice") != null && num(p.get("netprice")) != 0 ? p.get("netprice") : p.get("avgnetprice");
            out.add(new BrokerPosition(symbols.toKite(str(p.get("symboltoken")), str(p.get("tradingsymbol"))), str(p.get("exchange")),
                    kiteProduct(str(p.get("producttype"))), (int) num(p.get("netqty")), num(avg), num(p.get("ltp")),
                    num(p.get("pnl"))));
        }
        return out;
    }

    @Override public List<BrokerOrder> orders() {
        return list(get(P + "/order/v1/getOrderBook")).stream().map(o -> order(map(o))).toList();
    }

    @Override public BrokerOrder order(String orderId) {
        Id id = Id.parse(orderId);
        if (id.unique() != null && !id.unique().isBlank()) {
            Map<String, Object> d = map(get(P + "/order/v1/details/" + id.unique()));
            if (!d.isEmpty()) return order(d);
        }
        return list(get(P + "/order/v1/getOrderBook")).stream().map(this::map)
                .filter(o -> id.orderId().equals(str(o.get("orderid")))).findFirst().map(this::order)
                .orElseThrow(() -> new TradingException("Angel One has no order " + id.orderId()));
    }

    // ---- orders -------------------------------------------------------------------------------------------------------

    @Override public String placeOrder(PlaceOrder o) {
        AngelScripMaster.Contract c = symbols.toAngel(o.exchange(), o.tradingsymbol());
        boolean stop = "SL".equals(o.orderType()) || "SL-M".equals(o.orderType());
        String variety = stop ? "STOPLOSS" : "NORMAL";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variety", variety);
        body.put("tradingsymbol", c.symbol());
        body.put("symboltoken", c.token());
        body.put("transactiontype", o.transactionType());
        body.put("exchange", o.exchange());
        body.put("ordertype", angelOrderType(o.orderType()));
        body.put("producttype", angelProduct(o.product()));
        body.put("duration", "DAY");
        body.put("price", o.price() == null ? "0" : String.valueOf(o.price()));
        if (o.triggerPrice() != null) body.put("triggerprice", String.valueOf(o.triggerPrice()));
        body.put("quantity", String.valueOf(o.quantity()));
        if (o.tag() != null) body.put("ordertag", o.tag().length() > 20 ? o.tag().substring(0, 20) : o.tag());
        if (algoIdField != null && o.algoId() != null && !o.algoId().isBlank()) body.put(algoIdField, o.algoId());
        Map<String, Object> d = map(post(P + "/order/v1/placeOrder", body));
        String orderId = str(d.get("orderid"));
        if (orderId == null || orderId.isBlank()) throw new TradingException("Angel One did not return an order id");
        return new Id(stop ? "S" : "N", orderId, str(d.get("uniqueorderid"))).format();
    }

    @Override public void cancelOrder(String orderId) {
        Id id = Id.parse(orderId);
        post(P + "/order/v1/cancelOrder", Map.of("variety", "S".equals(id.variety()) ? "STOPLOSS" : "NORMAL", "orderid", id.orderId()));
    }

    // ---- market data (normally the platform Kite session's job) -------------------------------------------------------

    @Override public Map<String, Double> ltp(List<String> instruments) {
        if (instruments.isEmpty()) return Map.of();
        Map<String, String> byToken = new HashMap<>();
        Map<String, List<String>> tokens = new LinkedHashMap<>();
        for (String i : instruments) {
            int colon = i.indexOf(':');
            AngelScripMaster.Contract c = symbols.toAngel(i.substring(0, colon), i.substring(colon + 1));
            byToken.put(c.token(), i);
            tokens.computeIfAbsent(i.substring(0, colon), x -> new ArrayList<>()).add(c.token());
        }
        Map<String, Object> d = map(post(P + "/market/v1/quote", Map.of("mode", "LTP", "exchangeTokens", tokens)));
        Map<String, Double> out = new LinkedHashMap<>();
        for (Object o : list(d.get("fetched"))) {
            Map<String, Object> q = map(o);
            String key = byToken.get(str(q.get("symbolToken")));
            if (key != null) out.put(key, num(q.get("ltp")));
        }
        return out;
    }

    /** Contracts are named the Kite way everywhere: the platform session's list. */
    @Override public List<OptionInstrument> optionsOf(com.tradeanalysis.common.market.Underlying underlying) {
        return options.all(underlying);
    }

    // ---- translation --------------------------------------------------------------------------------------------------

    /** An id handed out by {@link #placeOrder}; a bare Angel order id (no prefix) also works. */
    record Id(String variety, String orderId, String unique) {
        String format() {
            String full = ID_PREFIX + variety + ":" + orderId + ":" + (unique == null ? "" : unique);
            return full.length() <= 64 ? full : ID_PREFIX + variety + ":" + orderId + ":";   // fits trade_order.broker_order_id
        }
        static Id parse(String s) {
            if (s == null || !s.startsWith(ID_PREFIX)) return new Id("N", s, null);
            String[] p = s.substring(ID_PREFIX.length()).split(":", -1);
            return new Id(p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : null);
        }
    }

    static String angelProduct(String kite) {
        return switch (String.valueOf(kite)) {
            case "NRML" -> "CARRYFORWARD";
            case "CNC" -> "DELIVERY";
            default -> "INTRADAY";
        };
    }

    static String kiteProduct(String angel) {
        return switch (String.valueOf(angel).toUpperCase(Locale.ROOT)) {
            case "CARRYFORWARD" -> "NRML";
            case "DELIVERY" -> "CNC";
            case "INTRADAY" -> "MIS";
            default -> angel;
        };
    }

    static String angelOrderType(String kite) {
        return switch (String.valueOf(kite)) {
            case "LIMIT" -> "LIMIT";
            case "SL" -> "STOPLOSS_LIMIT";
            case "SL-M" -> "STOPLOSS_MARKET";
            default -> "MARKET";
        };
    }

    static String kiteOrderType(String angel) {
        return switch (String.valueOf(angel).toUpperCase(Locale.ROOT)) {
            case "STOPLOSS_LIMIT" -> "SL";
            case "STOPLOSS_MARKET" -> "SL-M";
            default -> angel;
        };
    }

    /** Angel's lower-case statuses ("complete", "trigger pending", …) in Kite's words. */
    static String kiteStatus(String angel) {
        return angel == null ? null : angel.trim().toUpperCase(Locale.ROOT);
    }

    private BrokerOrder order(Map<String, Object> o) {
        String status = kiteStatus(str(o.getOrDefault("orderstatus", o.get("status"))));
        if (status == null || status.isBlank()) status = kiteStatus(str(o.get("status")));
        return new BrokerOrder(str(o.get("orderid")), status,
                symbols.toKite(str(o.get("symboltoken")), str(o.get("tradingsymbol"))), str(o.get("exchange")),
                str(o.get("transactiontype")), (int) num(o.get("quantity")), (int) num(o.get("filledshares")),
                num(o.get("averageprice")), o.get("price") == null ? null : num(o.get("price")), kiteOrderType(str(o.get("ordertype"))),
                kiteProduct(str(o.get("producttype"))), str(o.get("text")), str(o.get("ordertag")),
                str(o.getOrDefault("updatetime", o.get("exchorderupdatetime"))));
    }

    // ---- http ---------------------------------------------------------------------------------------------------------

    private Object get(String path) { return call(c -> c.get().uri(path)); }

    private Object post(String path, Object body) {
        return call(c -> c.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    /** SmartAPI answers {status, message, errorcode, data}; a failure can come back as HTTP 200 with status false. */
    private Object call(Function<RestClient, RestClient.RequestHeadersSpec<?>> request) {
        Map<?, ?> body;
        try {
            body = request.apply(http).header("Authorization", "Bearer " + creds.jwtToken())
                    .header("X-PrivateKey", creds.apiKey()).accept(MediaType.APPLICATION_JSON).retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            throw new TradingException(message(e));
        }
        if (body == null) return null;
        if (Boolean.FALSE.equals(body.get("status")) || "false".equals(String.valueOf(body.get("status"))))
            throw new TradingException(message(str(body.get("message")), str(body.get("errorcode"))));
        return body.get("data");
    }

    private String message(RestClientResponseException e) {
        try {
            Map<?, ?> b = json.readValue(e.getResponseBodyAsString(), Map.class);
            if (b.get("message") != null) return message(str(b.get("message")), str(b.get("errorcode")));
        } catch (Exception ignored) { }
        int code = e.getStatusCode().value();
        return code == 401 || code == 403 ? "Angel One session expired: log in to Angel One again" : "Angel One error " + code;
    }

    /** AG8001/AG8002/AG8003: invalid or expired token. */
    static String message(String msg, String errorCode) {
        if (errorCode != null && (errorCode.equals("AG8001") || errorCode.equals("AG8002") || errorCode.equals("AG8003")))
            return "Angel One session expired: log in to Angel One again";
        return "Angel One: " + (msg == null || msg.isBlank() ? "request failed" : msg) + (errorCode == null || errorCode.isBlank() ? "" : " (" + errorCode + ")");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null || o.toString().isBlank()) return 0;
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
