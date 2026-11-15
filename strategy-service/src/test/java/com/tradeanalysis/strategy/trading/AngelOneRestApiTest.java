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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AngelOneRestApiTest {

    private static final String BASE = "https://angel.test/rest/secure/angelbroking";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://angel.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AngelScripMaster symbols = mock(AngelScripMaster.class);
    private final NiftyOptions options = mock(NiftyOptions.class);
    private final AngelOneRestApi api = new AngelOneRestApi(builder.build(),
            new AngelOneRestApi.Credentials("akey", "jwt-1", "A123"), symbols, options, new ObjectMapper());

    private static final AngelScripMaster.Contract CE = new AngelScripMaster.Contract("43912", "NIFTY25NOV2524000CE", "NIFTY",
            LocalDate.of(2025, 11, 25), 24000, "CE", 75);

    @Test
    void placesAnOrderWithAngelsSymbolTokenAndWords() {
        when(symbols.toAngel("NFO", "NIFTY25N2524000CE")).thenReturn(CE);
        server.expect(requestTo(BASE + "/order/v1/placeOrder")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer jwt-1")).andExpect(header("X-PrivateKey", "akey"))
                .andExpect(jsonPath("$.variety").value("STOPLOSS"))
                .andExpect(jsonPath("$.tradingsymbol").value("NIFTY25NOV2524000CE"))
                .andExpect(jsonPath("$.symboltoken").value("43912"))
                .andExpect(jsonPath("$.ordertype").value("STOPLOSS_LIMIT"))
                .andExpect(jsonPath("$.producttype").value("INTRADAY"))
                .andExpect(jsonPath("$.price").value("120.5"))
                .andExpect(jsonPath("$.triggerprice").value("120.0"))
                .andExpect(jsonPath("$.quantity").value("75"))
                .andExpect(jsonPath("$.ordertag").value("TAP7"))
                .andRespond(withSuccess("""
                        {"status":true,"message":"SUCCESS","errorcode":"","data":{"script":"NIFTY25NOV2524000CE",
                         "orderid":"201020000000080","uniqueorderid":"34reqfachdfih"}}""", MediaType.APPLICATION_JSON));

        String id = api.placeOrder(new KiteApi.PlaceOrder("NFO", "NIFTY25N2524000CE", "BUY", 75, "MIS", "SL", 120.5, 120.0, "TAP7"));

        assertThat(id).isEqualTo("AO:S:201020000000080:34reqfachdfih");
        assertThat(AngelOneRestApi.isAngelOrderId(id)).isTrue();
        server.verify();
    }

    @Test
    void readsAnOrderByItsUniqueIdInKitesWords() {
        when(symbols.toKite("43912", "NIFTY25NOV2524000CE")).thenReturn("NIFTY25N2524000CE");
        server.expect(requestTo(BASE + "/order/v1/details/34reqfachdfih")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":true,"message":"SUCCESS","data":{"orderid":"201020000000080","status":"complete","orderstatus":"complete",
                         "tradingsymbol":"NIFTY25NOV2524000CE","symboltoken":"43912","exchange":"NFO","transactiontype":"BUY",
                         "quantity":"75","filledshares":"75","averageprice":121.25,"price":0,"ordertype":"MARKET",
                         "producttype":"CARRYFORWARD","text":"","ordertag":"TAP7","updatetime":"25-Nov-2025 09:21:03"}}""",
                        MediaType.APPLICATION_JSON));

        KiteApi.BrokerOrder o = api.order("AO:N:201020000000080:34reqfachdfih");

        assertThat(o.orderId()).isEqualTo("201020000000080");
        assertThat(o.status()).isEqualTo("COMPLETE");
        assertThat(OrderService.mapStatus(o.status())).isEqualTo(TradeOrder.Status.COMPLETE);
        assertThat(o.tradingsymbol()).isEqualTo("NIFTY25N2524000CE");
        assertThat(o.filledQuantity()).isEqualTo(75);
        assertThat(o.averagePrice()).isEqualTo(121.25);
        assertThat(o.product()).isEqualTo("NRML");
    }

    @Test
    void cancelsWithTheVarietyItWasPlacedWith() {
        server.expect(requestTo(BASE + "/order/v1/cancelOrder")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.variety").value("NORMAL")).andExpect(jsonPath("$.orderid").value("201020000000080"))
                .andRespond(withSuccess("{\"status\":true,\"message\":\"SUCCESS\",\"data\":{\"orderid\":\"201020000000080\"}}",
                        MediaType.APPLICATION_JSON));

        api.cancelOrder("AO:N:201020000000080:34reqfachdfih");
        server.verify();
    }

    @Test
    void positionsAndMarginsComeBackInKitesShapes() {
        when(symbols.toKite("43912", "NIFTY25NOV2524000CE")).thenReturn("NIFTY25N2524000CE");
        server.expect(requestTo(BASE + "/order/v1/getPosition")).andRespond(withSuccess("""
                {"status":true,"message":"SUCCESS","data":[{"tradingsymbol":"NIFTY25NOV2524000CE","symboltoken":"43912","exchange":"NFO",
                 "producttype":"INTRADAY","netqty":"75","netprice":"120.00","ltp":"130.5","pnl":"787.50"}]}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/user/v1/getRMS")).andRespond(withSuccess("""
                {"status":true,"message":"SUCCESS","data":{"net":"48000.00","availablecash":"50000.00","utiliseddebits":"2000.00"}}""",
                MediaType.APPLICATION_JSON));

        List<KiteApi.BrokerPosition> p = api.positions();
        assertThat(p).containsExactly(new KiteApi.BrokerPosition("NIFTY25N2524000CE", "NFO", "MIS", 75, 120.0, 130.5, 787.5));
        assertThat(api.margins()).isEqualTo(new KiteApi.Margins(50_000, 48_000, 2_000));
    }

    @Test
    void noPositionsIsAnEmptyList() {
        server.expect(requestTo(BASE + "/order/v1/getPosition"))
                .andRespond(withSuccess("{\"status\":true,\"message\":\"SUCCESS\",\"data\":null}", MediaType.APPLICATION_JSON));
        assertThat(api.positions()).isEmpty();
    }

    @Test
    void anExpiredTokenAsksToLogInAgain() {
        server.expect(requestTo(BASE + "/user/v1/getProfile")).andRespond(withSuccess(
                "{\"status\":false,\"message\":\"Invalid Token\",\"errorcode\":\"AG8001\",\"data\":null}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(api::profile).isInstanceOf(TradingException.class).hasMessageContaining("log in to Angel One again");
    }

    @Test
    void anHttpErrorShowsAngelsMessage() {
        when(symbols.toAngel("NFO", "NIFTY25N2524000CE")).thenReturn(CE);
        server.expect(requestTo(BASE + "/order/v1/placeOrder")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":false,\"message\":\"Invalid quantity\",\"errorcode\":\"AB1018\"}"));
        assertThatThrownBy(() -> api.placeOrder(new KiteApi.PlaceOrder("NFO", "NIFTY25N2524000CE", "BUY", 7, "MIS", "MARKET", null, null, null)))
                .hasMessageContaining("Angel One: Invalid quantity (AB1018)");
    }

    @Test
    void translatesProductsAndOrderTypesBothWays() {
        assertThat(AngelOneRestApi.angelProduct("MIS")).isEqualTo("INTRADAY");
        assertThat(AngelOneRestApi.angelProduct("NRML")).isEqualTo("CARRYFORWARD");
        assertThat(AngelOneRestApi.kiteProduct("CARRYFORWARD")).isEqualTo("NRML");
        assertThat(AngelOneRestApi.angelOrderType("SL-M")).isEqualTo("STOPLOSS_MARKET");
        assertThat(AngelOneRestApi.kiteOrderType("STOPLOSS_MARKET")).isEqualTo("SL-M");
        assertThat(OrderService.mapStatus(AngelOneRestApi.kiteStatus("rejected"))).isEqualTo(TradeOrder.Status.REJECTED);
        assertThat(OrderService.mapStatus(AngelOneRestApi.kiteStatus("trigger pending"))).isEqualTo(TradeOrder.Status.OPEN);
        assertThat(AngelOneRestApi.Id.parse("201020000000080")).isEqualTo(new AngelOneRestApi.Id("N", "201020000000080", null));
    }
}
