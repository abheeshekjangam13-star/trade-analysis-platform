package com.tradeanalysis.strategy.trading;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** With live trading enabled, orders go to (mocked) Kite and their status follows Kite's. */
@SpringBootTest(properties = "trading.live.enabled=true")
@ActiveProfiles("it")
class LiveOrderFlowTest {

    @Autowired OrderService orders;
    @MockitoBean KiteApi kite;

    @Test
    void liveOrderIsSentToKiteThenSyncedAndCancellable() {
        when(kite.placeOrder(any())).thenReturn("K-1", "K-2");
        OrderRequest r = new OrderRequest("NFO", "NIFTY26N0324150CE", "BUY", 65, "MIS", "LIMIT", 123.6, null);
        TradeOrder o = orders.place(r, TradingMode.LIVE, "MANUAL", null, null, false);
        assertThat(o.getStatus()).isEqualTo(TradeOrder.Status.OPEN);
        assertThat(o.getBrokerOrderId()).isEqualTo("K-1");
        verify(kite).placeOrder(new KiteApi.PlaceOrder("NFO", "NIFTY26N0324150CE", "BUY", 65, "MIS", "LIMIT", 123.6, null, "TAPM"));

        when(kite.order("K-1")).thenReturn(new KiteApi.BrokerOrder("K-1", "COMPLETE", "NIFTY26N0324150CE", "NFO", "BUY", 65, 65,
                121.4, 123.6, "LIMIT", "MIS", null, "TAPM", null));
        orders.syncLiveOrders();
        assertThat(orders.cancel(orders.place(r, TradingMode.LIVE, "MANUAL", null, null, false).getId()).getStatus())
                .isEqualTo(TradeOrder.Status.CANCELLED);
        verify(kite).cancelOrder("K-2");
    }

    @Test
    void brokerRejectionIsRecorded() {
        when(kite.placeOrder(any())).thenThrow(new TradingException("Kite: Insufficient funds"));
        TradeOrder o = orders.place(new OrderRequest("NFO", "X", "BUY", 65, "MIS", "MARKET", null, null), TradingMode.LIVE, "MANUAL", null, null, false);
        assertThat(o.getStatus()).isEqualTo(TradeOrder.Status.REJECTED);
        assertThat(o.getMessage()).contains("Insufficient funds");
    }

    @Test
    void kiteStatusesMapToOrderStatuses() {
        assertThat(OrderService.mapStatus("TRIGGER PENDING")).isEqualTo(TradeOrder.Status.OPEN);
        assertThat(OrderService.mapStatus("COMPLETE")).isEqualTo(TradeOrder.Status.COMPLETE);
        assertThat(OrderService.mapStatus("REJECTED")).isEqualTo(TradeOrder.Status.REJECTED);
        assertThat(OrderService.mapStatus("CANCELLED")).isEqualTo(TradeOrder.Status.CANCELLED);
    }
}
