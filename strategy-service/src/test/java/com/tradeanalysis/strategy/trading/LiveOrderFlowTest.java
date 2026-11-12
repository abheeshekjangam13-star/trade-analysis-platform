package com.tradeanalysis.strategy.trading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** With live trading on, orders go to (mocked) Kite and their status follows Kite's; off, only exits go out. */
@SpringBootTest
@ActiveProfiles("it")
class LiveOrderFlowTest {

    @Autowired OrderService orders;
    @MockitoBean KiteApi kite;
    @Autowired TradingControlRepository controls;

    @BeforeEach
    void liveOn() { live(true); }

    @AfterEach
    void liveOffAgain() { live(false); }   // the default, for the other tests sharing this database

    private void live(boolean on) {
        TradingControl c = controls.get();
        c.setLiveEnabled(on, null);
        controls.save(c);
    }

    @Test
    void offRefusesNewLiveOrdersButStillSendsExitsAndPaper() {
        live(false);
        try {
            when(kite.placeOrder(any())).thenReturn("K-9");
            OrderRequest r = new OrderRequest("NFO", "NIFTY26N0324150CE", "BUY", 65, "MIS", "MARKET", null, null);
            TradeOrder entry = orders.place(r, TradingMode.LIVE, "MANUAL", null, null, false);
            assertThat(entry.getStatus()).isEqualTo(TradeOrder.Status.REJECTED);
            assertThat(entry.getMessage()).isEqualTo(OrderService.LIVE_OFF);
            verify(kite, never()).placeOrder(any());
            // Closing a position (reducing) still goes to Kite, so open live positions can always be exited.
            OrderRequest exit = new OrderRequest("NFO", "NIFTY26N0324150CE", "SELL", 65, "MIS", "MARKET", null, null);
            assertThat(orders.place(exit, TradingMode.LIVE, "STRATEGY", null, null, true).getBrokerOrderId()).isEqualTo("K-9");
            assertThat(orders.liveEnabled()).isFalse();
        } finally {
            live(true);
        }
    }

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
