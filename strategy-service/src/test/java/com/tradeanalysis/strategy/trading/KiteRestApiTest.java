package com.tradeanalysis.strategy.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KiteRestApiTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final KiteSession session = mock(KiteSession.class);
    private final KiteRestApi api;

    KiteRestApiTest() {
        KiteSession.Credentials c = new KiteSession.Credentials("key", "tok", "AB1234", "Abheeshek");
        when(session.require()).thenReturn(c);
        when(session.current()).thenReturn(Optional.of(c));
        api = new KiteRestApi(builder, session, new ObjectMapper(), "https://kite.test");
    }

    @Test
    void placesARegularOrderWithTheFormKiteExpects() {
        server.expect(requestTo("https://kite.test/orders/regular")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "token key:tok")).andExpect(header("X-Kite-Version", "3"))
                .andExpect(content().formDataContains(java.util.Map.of("tradingsymbol", "NIFTY26N0324150CE", "exchange", "NFO",
                        "transaction_type", "BUY", "quantity", "130", "product", "MIS", "order_type", "LIMIT", "price", "123.6",
                        "validity", "DAY", "tag", "TAP56")))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"order_id\":\"151220000000000\"}}", MediaType.APPLICATION_JSON));
        String id = api.placeOrder(new KiteApi.PlaceOrder("NFO", "NIFTY26N0324150CE", "BUY", 130, "MIS", "LIMIT", 123.6, null, "TAP56"));
        assertThat(id).isEqualTo("151220000000000");
        server.verify();
    }

    @Test
    void kiteErrorsBecomeReadableMessages() {
        server.expect(requestTo("https://kite.test/orders/regular"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"error\",\"message\":\"Insufficient funds\",\"error_type\":\"MarginException\"}"));
        assertThatThrownBy(() -> api.placeOrder(new KiteApi.PlaceOrder("NFO", "X", "BUY", 65, "MIS", "MARKET", null, null, null)))
                .isInstanceOf(TradingException.class).hasMessageContaining("Kite: Insufficient funds");
    }

    @Test
    void readsTheLastStateOfAnOrderAndLtp() {
        server.expect(requestTo("https://kite.test/orders/42")).andRespond(withSuccess(
                "{\"data\":[{\"order_id\":\"42\",\"status\":\"OPEN\",\"filled_quantity\":0},{\"order_id\":\"42\",\"status\":\"COMPLETE\","
                        + "\"filled_quantity\":130,\"quantity\":130,\"average_price\":121.4,\"tradingsymbol\":\"S\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kite.test/quote/ltp?i=NFO:S")).andRespond(withSuccess(
                "{\"data\":{\"NFO:S\":{\"instrument_token\":1,\"last_price\":99.5}}}", MediaType.APPLICATION_JSON));
        KiteApi.BrokerOrder o = api.order("42");
        assertThat(o.status()).isEqualTo("COMPLETE");
        assertThat(o.filledQuantity()).isEqualTo(130);
        assertThat(o.averagePrice()).isEqualTo(121.4);
        assertThat(api.ltp(List.of("NFO:S"))).containsEntry("NFO:S", 99.5);
    }

    @Test
    void parsesNiftyOptionsFromTheNfoInstrumentList() {
        String csv = """
                instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
                1,1,NIFTY26N0324150CE,"NIFTY",0,2026-11-03,24150,0.05,65,CE,NFO-OPT,NFO
                2,2,NIFTY26N0324150PE,"NIFTY",0,2026-11-03,24150,0.05,65,PE,NFO-OPT,NFO
                3,3,BANKNIFTY26N0450000CE,"BANKNIFTY",0,2026-11-04,50000,0.05,30,CE,NFO-OPT,NFO
                4,4,NIFTY26NOVFUT,"NIFTY",0,2026-11-24,0,0.1,65,FUT,NFO-FUT,NFO
                """;
        List<KiteApi.OptionInstrument> opts = KiteRestApi.parseNiftyOptions(csv);
        assertThat(opts).extracting(KiteApi.OptionInstrument::tradingsymbol).containsExactly("NIFTY26N0324150CE", "NIFTY26N0324150PE");
        assertThat(opts.get(0).expiry()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(opts.get(0).lotSize()).isEqualTo(65);
    }
}
