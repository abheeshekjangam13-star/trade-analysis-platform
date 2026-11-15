package com.tradeanalysis.strategy.trading;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.tradeanalysis.common.market.Underlying;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/**
 * Translates option contracts between Kite and Angel One. Strategies, positions and the UI name contracts the Kite way
 * (NIFTY25N2524000CE, from the platform session's NFO list in {@link NiftyOptions}); Angel One orders need Angel's own
 * symbol and its numeric symbol token, from Angel's scrip master (OpenAPIScripMaster.json, loaded once a day, only the
 * NFO index options of the indices strategies trade). A contract matches on index, expiry, strike and CE/PE.
 */
@Component
public class AngelScripMaster {

    private static final Logger log = LoggerFactory.getLogger(AngelScripMaster.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final DateTimeFormatter EXPIRY = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("ddMMMyyyy").toFormatter(Locale.ENGLISH);

    /** One Angel One option contract. */
    public record Contract(String token, String symbol, String name, LocalDate expiry, int strike, String optionType, int lotSize) {
        String key() { return key(name, expiry, strike, optionType); }
        static String key(String name, LocalDate expiry, int strike, String type) { return name + "|" + expiry + "|" + strike + "|" + type; }
    }

    private final RestClient http;
    private final String url;
    private final NiftyOptions options;
    private final Clock clock;

    private Map<String, Contract> byKey = Map.of();
    private Map<String, Contract> byToken = Map.of();
    private LocalDate loadedOn;

    public AngelScripMaster(RestClient.Builder builder, NiftyOptions options, Clock clock,
                            @Value("${angel.scrip-master-url:https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json}") String url) {
        this.http = builder.clone().build(); this.options = options; this.clock = clock; this.url = url;
    }

    /** Angel One's contract for a Kite symbol (NFO index options only). */
    public Contract toAngel(String exchange, String kiteSymbol) {
        if (!"NFO".equals(exchange))
            throw new TradingException("Angel One orders support NFO index options only (got " + exchange + ":" + kiteSymbol + ")");
        for (Underlying u : Underlying.values()) {
            if (!kiteSymbol.startsWith(u.optionName)) continue;
            Optional<KiteApi.OptionInstrument> k = options.find(u, kiteSymbol);
            if (k.isEmpty()) continue;
            KiteApi.OptionInstrument o = k.get();
            Contract c = contracts().get(Contract.key(u.optionName, o.expiry(), o.strike(), o.optionType()));
            if (c != null) return c;
            throw new TradingException("Angel One does not list " + kiteSymbol + " (" + u.optionName + " " + o.expiry() + " "
                    + o.strike() + " " + o.optionType() + ")");
        }
        throw new TradingException("Unknown contract " + kiteSymbol + ": Angel One orders need a NIFTY or BANKNIFTY option listed on NFO");
    }

    /** The Kite symbol of an Angel One contract (by its symbol token), or the Angel symbol when there is no match. */
    public String toKite(String token, String angelSymbol) {
        if (token == null) return angelSymbol;
        Contract c;
        try {
            contracts();
            c = byToken.get(token);
        } catch (RuntimeException e) {
            return angelSymbol;
        }
        if (c == null) return angelSymbol;
        final Contract found = c;
        try {
            return options.all(Underlying.of(found.name())).stream()
                    .filter(o -> o.expiry().equals(found.expiry()) && o.strike() == found.strike() && o.optionType().equals(found.optionType()))
                    .map(KiteApi.OptionInstrument::tradingsymbol).findFirst().orElse(angelSymbol);
        } catch (RuntimeException e) {   // platform Kite session not connected: show Angel's name
            return angelSymbol;
        }
    }

    /** The index option contracts, loaded once per IST day. */
    synchronized Map<String, Contract> contracts() {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        if (!byKey.isEmpty() && today.equals(loadedOn)) return byKey;
        List<Contract> list;
        try {
            list = http.get().uri(url).exchange((req, res) -> {
                if (!res.getStatusCode().is2xxSuccessful()) throw new TradingException("Angel One scrip master: HTTP " + res.getStatusCode().value());
                try (InputStream in = res.getBody()) { return parse(in); }
            });
        } catch (TradingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TradingException("Could not load Angel One's scrip master: " + e.getMessage());
        }
        if (list == null || list.isEmpty()) throw new TradingException("Angel One's scrip master has no NIFTY/BANKNIFTY options");
        load(list);
        loadedOn = today;
        log.info("ANGEL_SCRIP_MASTER loaded {} index options", list.size());
        return byKey;
    }

    /** Replaces the contracts (tests). */
    synchronized void load(List<Contract> list) {
        Map<String, Contract> k = new HashMap<>(), t = new HashMap<>();
        for (Contract c : list) { k.put(c.key(), c); t.put(c.token(), c); }
        byKey = k; byToken = t; loadedOn = LocalDate.now(clock.withZone(IST));
    }

    /**
     * The NFO index options (OPTIDX) of NIFTY and BANKNIFTY from the scrip master: a JSON array of objects like
     * {"token":"43912","symbol":"NIFTY25NOV2524000CE","name":"NIFTY","expiry":"25NOV2025","strike":"2400000.000000",
     * "lotsize":"75","instrumenttype":"OPTIDX","exch_seg":"NFO","tick_size":"5.000000"}. Strikes are in paise.
     * Read as a stream: the whole file is tens of megabytes.
     */
    static List<Contract> parse(InputStream in) throws IOException {
        Set<String> names = new HashSet<>();
        for (Underlying u : Underlying.values()) names.add(u.optionName);
        List<Contract> out = new ArrayList<>();
        try (JsonParser p = new JsonFactory().createParser(in)) {
            if (p.nextToken() != JsonToken.START_ARRAY) return out;
            Map<String, String> row = new HashMap<>();
            while (p.nextToken() == JsonToken.START_OBJECT) {
                row.clear();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String field = p.currentName();
                    JsonToken v = p.nextToken();
                    if (v.isStructStart()) p.skipChildren();
                    else row.put(field, v == JsonToken.VALUE_NULL ? null : p.getText());
                }
                if (!"NFO".equals(row.get("exch_seg")) || !"OPTIDX".equals(row.get("instrumenttype")) || !names.contains(row.get("name"))) continue;
                String symbol = row.get("symbol");
                if (symbol == null || symbol.length() < 3) continue;
                String type = symbol.substring(symbol.length() - 2);
                if (!type.equals("CE") && !type.equals("PE")) continue;
                try {
                    out.add(new Contract(row.get("token"), symbol, row.get("name"), LocalDate.parse(row.get("expiry"), EXPIRY),
                            (int) Math.round(Double.parseDouble(row.get("strike")) / 100.0), type,
                            (int) Math.round(Double.parseDouble(Objects.requireNonNullElse(row.get("lotsize"), "0")))));
                } catch (RuntimeException e) {
                    // skip a malformed row
                }
            }
        }
        return out;
    }
}
