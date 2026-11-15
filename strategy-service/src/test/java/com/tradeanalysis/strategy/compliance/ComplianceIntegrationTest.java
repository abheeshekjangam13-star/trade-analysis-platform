package com.tradeanalysis.strategy.compliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.auth.AuthService;
import com.tradeanalysis.strategy.auth.TokenService;
import com.tradeanalysis.strategy.trading.KiteApi;
import com.tradeanalysis.strategy.trading.MutableClock;
import com.tradeanalysis.strategy.trading.OrderRequest;
import com.tradeanalysis.strategy.trading.OrderService;
import com.tradeanalysis.strategy.trading.TradeOrder;
import com.tradeanalysis.strategy.trading.TradingControl;
import com.tradeanalysis.strategy.trading.TradingControlRepository;
import com.tradeanalysis.strategy.trading.TradingMode;
import com.tradeanalysis.strategy.user.AppUserRepository;
import com.tradeanalysis.strategy.user.ClientIds;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Broker / exchange readiness: PAN and the name as on the PAN card at sign-up, the client ID (also a login),
 * two-factor sign-in, the risk disclosure, exchange Algo IDs on live orders, and the compliance audit trail.
 */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false",
        "spring.datasource.url=jdbc:h2:mem:compliance-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class ComplianceIntegrationTest {

    @TestConfiguration
    static class Clocks {
        @Bean @Primary MutableClock testClock() { return new MutableClock(Instant.parse("2026-11-03T05:00:00Z")); }   // Tue 10:30 IST
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @Autowired MutableClock clock;
    @Autowired OrderService orders;
    @Autowired TradingControlRepository controls;
    @Autowired Compliance compliance;
    @MockitoBean KiteApi kite;

    private static final String PAN = "ABCPM1234Q";

    private JsonNode call(MockHttpServletRequestBuilder req, String asUser, int expected) throws Exception {
        if (asUser != null)
            req.header("Authorization", "Bearer " + tokens.issue(users.findByUsernameIgnoreCase(asUser).orElseThrow()).token());
        var res = mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(expected)).andReturn().getResponse();
        String body = res.getContentAsString();
        if (body.isEmpty() && res.getErrorMessage() != null) return json.createObjectNode().put("message", res.getErrorMessage());
        return body.isEmpty() ? null : json.readTree(body);
    }

    private long id(String username) { return users.findByUsernameIgnoreCase(username).orElseThrow().getId(); }

    private static String signup(String user, String phone, String fullName, String pan) {
        return "{\"username\":\"" + user + "\",\"password\":\"Pass1234\",\"email\":\"" + user + "@example.com\",\"phone\":\"" + phone
                + "\",\"fullName\":" + (fullName == null ? "null" : "\"" + fullName + "\"") + ",\"pan\":" + (pan == null ? "null" : "\"" + pan + "\"")
                + ",\"acceptTerms\":true,\"plan\":\"FREE\"}";
    }

    private String totp(String secret, long secondsFromNow) {
        return TwoFactor.code(TwoFactor.base32Decode(secret), (clock.instant().getEpochSecond() + secondsFromNow) / 30);
    }

    @Test @Order(1)
    void signUpNeedsAValidUniquePanAndTheNameAsOnIt() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 200);

        assertThat(call(post("/api/auth/signup").content(signup("meera", "9876511111", "Meera Rao", null)), null, 400).get("message").asText()).contains("valid PAN");
        call(post("/api/auth/signup").content(signup("meera", "9876511111", "Meera Rao", "ABCDE12345")), null, 400);   // wrong format
        call(post("/api/auth/signup").content(signup("meera", "9876511111", "Meera Rao", "ABCXM1234Q")), null, 400);   // 4th letter not a holder type
        assertThat(call(post("/api/auth/signup").content(signup("meera", "9876511111", null, PAN)), null, 400).get("message").asText()).contains("as on your PAN card");
        call(post("/api/auth/signup").content(signup("meera", "9876511111", "M3era", PAN)), null, 400);
        assertThat(users.findByUsernameIgnoreCase("meera")).isEmpty();

        JsonNode r = call(post("/api/auth/signup").content(signup("meera", "9876511111", "  Meera   Rao ", "abcpm 1234q")), null, 201);
        String clientCode = r.get("clientCode").asText();
        assertThat(clientCode).matches("SG\\d{6}");
        assertThat(ClientIds.userId(clientCode)).contains(id("meera"));
        assertThat(jdbc.queryForObject("SELECT full_name FROM app_user WHERE username = 'meera'", String.class)).isEqualTo("Meera Rao");

        // One PAN, one account (spaces and case don't matter).
        assertThat(call(post("/api/auth/signup").content(signup("meera2", "9876522222", "Meera Rao", "ABCPM1234Q")), null, 409).get("message").asText())
                .contains("already linked");
        assertThat(users.findByUsernameIgnoreCase("meera2")).isEmpty();

        // Stored encrypted and fingerprinted; shown masked.
        assertThat(jdbc.queryForObject("SELECT pan_enc FROM user_pan WHERE user_id = ?", String.class, id("meera"))).doesNotContain(PAN);
        call(post("/api/users/" + id("meera") + "/approve").content("{}"), "admin", 200);
        JsonNode kyc = call(get("/api/users/me/kyc"), "meera", 200);
        assertThat(kyc.get("clientCode").asText()).isEqualTo(clientCode);
        assertThat(kyc.get("panMasked").asText()).isEqualTo("XXXXX1234X");
        assertThat(kyc.get("nameOnPan").asText()).isEqualTo("Meera Rao");
        assertThat(kyc.get("verified").asBoolean()).isFalse();
        assertThat(kyc.toString()).doesNotContain(PAN);
        assertThat(call(get("/api/users/me"), "meera", 200).get("clientCode").asText()).isEqualTo(clientCode);

        // A user adds the PAN once; changes go through an admin.
        call(put("/api/users/me/kyc").content("{\"pan\":\"ABCPN1234Q\",\"nameOnPan\":\"Meera Rao\"}"), "meera", 400);
        call(get("/api/users/kyc"), "meera", 403);
        call(get("/api/users/" + id("meera") + "/kyc/pan"), "meera", 403);
        assertThat(call(get("/api/users/" + id("meera") + "/kyc/pan"), "admin", 200).get("pan").asText()).isEqualTo(PAN);
        assertThat(call(put("/api/users/" + id("meera") + "/kyc/verified").content("{\"verified\":true}"), "admin", 200).get("verified").asBoolean()).isTrue();
        assertThat(call(get("/api/users/kyc"), "admin", 200).toString()).contains(clientCode, "XXXXX1234X");

        // The client ID works as the login name.
        assertThat(call(post("/api/auth/login").content("{\"username\":\"" + clientCode.toLowerCase() + "\",\"password\":\"Pass1234\"}"), null, 200)
                .get("user").get("username").asText()).isEqualTo("meera");
        call(post("/api/auth/login").content("{\"username\":\"" + clientCode + "\",\"password\":\"Wrong1234\"}"), null, 401);
    }

    @Test @Order(2)
    void twoFactorSignInWithAnAuthenticatorApp() throws Exception {
        assertThat(call(get("/api/auth/2fa"), "meera", 200).get("enabled").asBoolean()).isFalse();
        JsonNode setup = call(post("/api/auth/2fa/setup"), "meera", 200);
        String secret = setup.get("secret").asText();
        assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/SysGrow").contains("secret=" + secret);

        call(post("/api/auth/2fa/enable").content("{\"code\":\"000000\"}".replace("000000", totp(secret, 0).equals("000000") ? "111111" : "000000")), "meera", 400);
        String first = totp(secret, 0);
        assertThat(call(post("/api/auth/2fa/enable").content("{\"code\":\"" + first + "\"}"), "meera", 200).get("enabled").asBoolean()).isTrue();
        call(post("/api/auth/2fa/setup"), "meera", 400);   // already on

        // Login now needs the code; a used code doesn't work twice.
        JsonNode need = call(post("/api/auth/login").content("{\"username\":\"meera\",\"password\":\"Pass1234\"}"), null, 401);
        assertThat(need.get("message").asText()).isEqualTo(AuthService.CODE_REQUIRED);
        call(post("/api/auth/login").content("{\"username\":\"meera\",\"password\":\"Pass1234\",\"code\":\"" + first + "\"}"), null, 401);
        clock.set(clock.instant().plusSeconds(30));
        call(post("/api/auth/login").content("{\"username\":\"meera\",\"password\":\"Pass1234\",\"code\":\"" + totp(secret, 0) + "\"}"), null, 200);

        // Off needs a current code; an admin can reset it (lost phone).
        clock.set(clock.instant().plusSeconds(30));
        call(post("/api/auth/2fa/disable").content("{\"code\":\"12\"}"), "meera", 400);
        call(delete("/api/users/" + id("meera") + "/2fa"), "meera", 403);
        call(delete("/api/users/" + id("meera") + "/2fa"), "admin", 204);
        assertThat(call(get("/api/auth/2fa"), "meera", 200).get("enabled").asBoolean()).isFalse();
        call(post("/api/auth/login").content("{\"username\":\"meera\",\"password\":\"Pass1234\"}"), null, 200);
    }

    @Test @Order(3)
    void liveTradingNeedsTheDisclosureAndWhatTheAdminSwitchedOn() throws Exception {
        long meera = id("meera"), admin = id("admin");
        jdbc.update("UPDATE app_user SET plan = 'PREMIUM', plan_expires_at = NULL WHERE id = ?", meera);   // live trading plan
        JsonNode d = call(get("/api/trading/risk-disclosure"), "meera", 200);
        assertThat(d.get("accepted").asBoolean()).isFalse();
        assertThat(d.get("points").size()).isGreaterThan(3);
        assertThat(compliance.liveRefusal(meera)).contains("risk disclosure");
        assertThat(compliance.liveRefusal(admin)).isNull();   // admins don't need the disclosure

        call(post("/api/trading/risk-disclosure/accept").content("{\"version\":\"1999-01\"}"), "meera", 400);   // an old version
        assertThat(call(post("/api/trading/risk-disclosure/accept").content("{\"version\":\"" + RiskDisclosure.VERSION + "\"}"), "meera", 200)
                .get("accepted").asBoolean()).isTrue();
        assertThat(compliance.liveRefusal(meera)).isNull();

        call(put("/api/trading/compliance/settings").content("{\"liveNeedsTwoFactor\":true,\"liveNeedsPan\":true}"), "meera", 403);
        call(put("/api/trading/compliance/settings").content("{\"liveNeedsTwoFactor\":true,\"liveNeedsPan\":true}"), "admin", 200);
        assertThat(compliance.liveRefusal(meera)).contains("two-factor");                         // PAN verified, no 2FA
        call(put("/api/users/" + meera + "/kyc/verified").content("{\"verified\":false}"), "admin", 200);
        assertThat(compliance.liveRefusal(meera)).contains("waiting for verification");
        assertThat(compliance.liveRefusal(admin)).contains("Add your PAN");                       // admins too
        call(put("/api/trading/compliance/settings").content("{\"liveNeedsTwoFactor\":false,\"liveNeedsPan\":false}"), "admin", 200);

        // Grievance contact: set by an admin, open to everyone.
        call(put("/api/trading/compliance/settings").content("{\"grievanceEmail\":\"not-an-email\"}"), "admin", 400);
        call(put("/api/trading/compliance/settings").content("{\"grievanceName\":\"Grievance Officer\",\"grievanceEmail\":\"grievance@sysgrow.in\"}"), "admin", 200);
        JsonNode g = call(get("/api/auth/grievance"), null, 200);
        assertThat(g.get("email").asText()).isEqualTo("grievance@sysgrow.in");
        assertThat(g.get("name").asText()).isEqualTo("Grievance Officer");
    }

    @Test @Order(4)
    void liveOrdersCarryTheExchangeAlgoId() throws Exception {
        TradingControl c = controls.get();
        c.setLiveEnabled(true, null);
        c.setLiveRestriction(false, null, null);
        controls.save(c);
        when(kite.placeOrder(any())).thenReturn("K-1", "K-2", "K-3");
        OrderRequest r = new OrderRequest("NFO", "NIFTY26N0324150CE", "BUY", 65, "MIS", "LIMIT", 123.6, null);

        call(get("/api/trading/compliance/algo-ids"), "meera", 403);
        call(put("/api/trading/compliance/algo-ids").content("{\"scope\":\"NOPE\",\"algoId\":\"X1\"}"), "admin", 400);
        call(put("/api/trading/compliance/algo-ids").content("{\"scope\":\"MANUAL\",\"algoId\":\"bad id!\"}"), "admin", 400);
        call(put("/api/trading/compliance/algo-ids").content("{\"scope\":\"DEFAULT\",\"algoId\":\"NSEALGO-100\"}"), "admin", 200);
        JsonNode ids = call(put("/api/trading/compliance/algo-ids").content("{\"scope\":\"MANUAL\",\"algoId\":\"NSEALGO-200\"}"), "admin", 200);
        assertThat(ids.get("algoIds").get("MANUAL").asText()).isEqualTo("NSEALGO-200");

        TradeOrder manual = orders.place(r, TradingMode.LIVE, "MANUAL", null, null, false);
        assertThat(manual.getAlgoId()).isEqualTo("NSEALGO-200");
        verify(kite).placeOrder(new KiteApi.PlaceOrder("NFO", "NIFTY26N0324150CE", "BUY", 65, "MIS", "LIMIT", 123.6, null, "TAPM", "NSEALGO-200"));

        // A strategy order: its type has no own Algo ID, so DEFAULT; the strategy's settings are kept with the order.
        jdbc.update("INSERT INTO strategy (name, type, created_at, created_by_user_id) VALUES ('algo test', 'SMA_CROSSOVER', CURRENT_TIMESTAMP, ?)", id("admin"));
        long sid = jdbc.queryForObject("SELECT id FROM strategy WHERE name = 'algo test'", Long.class);
        TradeOrder auto = orders.place(r, TradingMode.LIVE, "STRATEGY", sid, null, false);
        assertThat(auto.getAlgoId()).isEqualTo("NSEALGO-100");
        assertThat(auto.getStrategySnapshot()).contains("\"type\":\"SMA_CROSSOVER\"", "algo test");
        assertThat(orders.place(r, TradingMode.PAPER, "MANUAL", null, null, false).getAlgoId()).isNull();   // paper: no exchange ID

        // Required, and none set: new live entries are refused, exits still go out.
        call(put("/api/trading/compliance/algo-ids").content("{\"scope\":\"MANUAL\",\"algoId\":\"\"}"), "admin", 200);
        call(put("/api/trading/compliance/algo-ids").content("{\"scope\":\"DEFAULT\",\"algoId\":\" \"}"), "admin", 200);
        call(put("/api/trading/compliance/settings").content("{\"algoIdRequired\":true}"), "admin", 200);
        TradeOrder refused = orders.place(r, TradingMode.LIVE, "MANUAL", null, null, false);
        assertThat(refused.getStatus()).isEqualTo(TradeOrder.Status.REJECTED);
        assertThat(refused.getMessage()).isEqualTo(OrderService.NO_ALGO_ID);
        OrderRequest exit = new OrderRequest("NFO", "NIFTY26N0324150CE", "SELL", 65, "MIS", "MARKET", null, null);
        assertThat(orders.place(exit, TradingMode.LIVE, "MANUAL", null, null, true).getBrokerOrderId()).isEqualTo("K-3");
        call(put("/api/trading/compliance/settings").content("{\"algoIdRequired\":false}"), "admin", 200);
    }

    @Test @Order(5)
    void everyComplianceChangeIsInTheAuditTrail() throws Exception {
        call(get("/api/trading/compliance/audit"), "meera", 403);
        call(get("/api/trading/compliance/audit?from=2026-11-10&to=2026-11-01"), "admin", 400);
        String trail = call(get("/api/trading/compliance/audit?from=2026-10-01&to=2026-11-30"), "admin", 200).toString();
        assertThat(trail).contains("PAN_VIEWED", "PAN_VERIFIED", "PAN_UNVERIFIED", "TWO_FACTOR_ON", "TWO_FACTOR_RESET",
                "RISK_DISCLOSURE_ACCEPTED", "COMPLIANCE_SETTINGS", "ALGO_ID_SET", "NSEALGO-200");
        assertThat(trail).doesNotContain(PAN);
    }
}
