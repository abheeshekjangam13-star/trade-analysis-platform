package com.tradeanalysis.strategy.live;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.strategy.live.ExitOrderGateway.OrderStatus;
import com.tradeanalysis.strategy.live.ExitOrderGateway.OrderUpdate;
import com.tradeanalysis.strategy.model.NiftyPositionState;
import com.tradeanalysis.strategy.model.NiftyPositionState.Status;
import com.tradeanalysis.strategy.repository.NiftyPositionStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static com.tradeanalysis.strategy.engine.Candles.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NiftyStopLossMonitorTest {

    private static final long STRATEGY = 34L;

    private final List<NiftyPositionState> db = new ArrayList<>();   // stands in for the H2 table
    private final List<Candle> history = new ArrayList<>();           // stands in for market-data-service
    private NiftyPositionStateRepository repo;
    private ExitOrderGateway orders;
    private final CandleSource candles = (after, now) -> history.stream()
            .filter(c -> c.timestamp().isAfter(after) && !c.timestamp().isAfter(now))
            .toList();

    @BeforeEach
    void setUp() {
        repo = mock(NiftyPositionStateRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            NiftyPositionState s = inv.getArgument(0);
            if (!db.contains(s)) db.add(s);
            return s;
        });
        when(repo.findFirstByStrategyIdAndStatusIn(eq(STRATEGY), any())).thenAnswer(inv -> {
            Collection<Status> statuses = inv.getArgument(1);
            return db.stream().filter(s -> statuses.contains(s.getStatus())).findFirst();
        });
        orders = mock(ExitOrderGateway.class);
    }

    private NiftyStopLossMonitor monitor() {
        return new NiftyStopLossMonitor(STRATEGY, repo, candles, orders);
    }

    /** Bullish CE entered on the 09:25 5-min candle (order at 09:30); reference 24100/24000 → initial SL 24050. */
    private NiftyPositionState bullishPosition() {
        return new NiftyPositionState(STRATEGY, DAY, 1, "BULLISH", "NIFTY25N0424050CE", 130,
                24100, 24000, 24050, at(9, 25), "ENTRY-1", "COMPLETE");
    }

    @Test
    void restartRestoresLastPersistedStopNotInitial() {
        NiftyStopLossMonitor first = monitor();
        first.open(bullishPosition());
        first.onCandleClosed(bar(9, 30, 24120, 24135, 24110, 24130), close1m(9, 30));
        first.onCandleClosed(bar(9, 31, 24130, 24145, 24125, 24140), close1m(9, 31));
        assertThat(db.get(0).getCurrentStopLoss()).isEqualTo(24125.0);

        NiftyStopLossMonitor afterRestart = monitor();
        afterRestart.restore(close1m(9, 31).plusSeconds(10));

        assertThat(afterRestart.currentStop()).contains(24125.0);
        assertThat(db.get(0).getLastProcessedCandle()).isEqualTo(at(9, 31));
    }

    @Test
    void missedCandlesAreRecoveredBeforeTrailing() {
        history.addAll(List.of(
                bar(9, 30, 24120, 24135, 24110, 24130),
                bar(9, 31, 24130, 24145, 24125, 24140),
                bar(9, 32, 24140, 24150, 24132, 24148)));
        NiftyStopLossMonitor m = monitor();
        m.open(bullishPosition());

        // WebSocket dropped 09:30 and 09:31; the 09:32 candle arrives first.
        m.onCandleClosed(bar(9, 32, 24140, 24150, 24132, 24148), close1m(9, 32));

        assertThat(m.currentStop()).contains(24132.0);
        assertThat(db.get(0).getLastProcessedCandle()).isEqualTo(at(9, 32));
    }

    @Test
    void restoreReplaysCandlesClosedWhileDown() {
        NiftyStopLossMonitor first = monitor();
        first.open(bullishPosition());
        history.addAll(List.of(
                bar(9, 30, 24120, 24135, 24110, 24130),
                bar(9, 31, 24130, 24145, 24125, 24140),
                bar(9, 32, 24140, 24150, 24132, 24148)));   // still forming at restart time

        NiftyStopLossMonitor restarted = monitor();
        restarted.restore(at(9, 32).plusSeconds(30));

        assertThat(restarted.currentStop()).contains(24125.0);
        assertThat(db.get(0).getLastProcessedCandle()).isEqualTo(at(9, 31));
    }

    @Test
    void bullishStopTriggersSingleExitAndClosesOnlyAfterFill() {
        when(orders.placeExit(any(), eq(130))).thenReturn("EXIT-1");
        when(orders.status("EXIT-1")).thenReturn(new OrderUpdate(OrderStatus.OPEN, 0, "pending"));
        NiftyStopLossMonitor m = monitor();
        m.open(bullishPosition());

        m.onSpotTick(24051, at(9, 31));
        verify(orders, never()).placeExit(any(), anyInt());

        m.onSpotTick(24050, at(9, 31));
        m.onSpotTick(24040, at(9, 31));
        m.onSpotTick(24030, at(9, 31));

        verify(orders, times(1)).placeExit(any(), eq(130));
        assertThat(db.get(0).getStatus()).isEqualTo(Status.EXIT_PENDING);   // order id ≠ filled

        when(orders.status("EXIT-1")).thenReturn(new OrderUpdate(OrderStatus.COMPLETE, 130, "filled"));
        m.verifyExit();
        assertThat(db.get(0).getStatus()).isEqualTo(Status.CLOSED);
        assertThat(m.position()).isEmpty();
    }

    @Test
    void bearishStopTriggersAtOrAboveStop() {
        when(orders.placeExit(any(), anyInt())).thenReturn("EXIT-2");
        when(orders.status("EXIT-2")).thenReturn(new OrderUpdate(OrderStatus.COMPLETE, 130, "filled"));
        NiftyStopLossMonitor m = monitor();
        m.open(new NiftyPositionState(STRATEGY, DAY, 1, "BEARISH", "NIFTY25N0424050PE", 130,
                24100, 24000, 24050, at(9, 25), "ENTRY-1", "COMPLETE"));

        m.onSpotTick(24049.95, at(9, 31));
        verify(orders, never()).placeExit(any(), anyInt());
        m.onSpotTick(24050, at(9, 31));

        assertThat(db.get(0).getStatus()).isEqualTo(Status.CLOSED);
    }

    @Test
    void restartDuringPendingExitDoesNotSendSecondOrder() {
        when(orders.placeExit(any(), anyInt())).thenReturn("EXIT-1");
        when(orders.status("EXIT-1")).thenReturn(new OrderUpdate(OrderStatus.OPEN, 0, "pending"));
        NiftyStopLossMonitor first = monitor();
        first.open(bullishPosition());
        first.onSpotTick(24000, at(9, 31));

        NiftyStopLossMonitor restarted = monitor();
        restarted.restore(at(9, 32));
        restarted.onSpotTick(23990, at(9, 32));

        verify(orders, times(1)).placeExit(any(), anyInt());
    }

    @Test
    void exitTimeoutIsNotRetriedBlindly() {
        when(orders.placeExit(any(), anyInt())).thenThrow(new RuntimeException("read timed out"));
        NiftyStopLossMonitor m = monitor();
        m.open(bullishPosition());

        m.onSpotTick(24000, at(9, 31));
        m.onSpotTick(23990, at(9, 31));

        verify(orders, times(1)).placeExit(any(), anyInt());
        assertThat(db.get(0).getStatus()).isEqualTo(Status.EXIT_PENDING);
    }

    @Test
    void rejectedExitAfterPartialFillResendsOnlyRemainder() {
        when(orders.placeExit(any(), eq(130))).thenReturn("EXIT-1");
        when(orders.placeExit(any(), eq(65))).thenReturn("EXIT-2");
        when(orders.status("EXIT-1")).thenReturn(new OrderUpdate(OrderStatus.CANCELLED, 65, "cancelled"));
        when(orders.status("EXIT-2")).thenReturn(new OrderUpdate(OrderStatus.COMPLETE, 65, "filled"));
        NiftyStopLossMonitor m = monitor();
        m.open(bullishPosition());

        m.onSpotTick(24000, at(9, 31));
        assertThat(db.get(0).getStatus()).isEqualTo(Status.OPEN);
        assertThat(db.get(0).getExitFilledQuantity()).isEqualTo(65);

        m.onSpotTick(23995, at(9, 31));
        verify(orders).placeExit(any(), eq(65));
        assertThat(db.get(0).getStatus()).isEqualTo(Status.CLOSED);
    }

    @Test
    void trailingStopsOnceExitIsPending() {
        when(orders.placeExit(any(), anyInt())).thenReturn("EXIT-1");
        when(orders.status("EXIT-1")).thenReturn(new OrderUpdate(OrderStatus.OPEN, 0, "pending"));
        NiftyStopLossMonitor m = monitor();
        m.open(bullishPosition());
        m.onSpotTick(24000, at(9, 29));

        m.onCandleClosed(bar(9, 30, 24120, 24135, 24110, 24130), close1m(9, 30));
        assertThat(db.get(0).getCurrentStopLoss()).isEqualTo(24050.0);
    }

}
