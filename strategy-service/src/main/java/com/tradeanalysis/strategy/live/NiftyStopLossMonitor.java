package com.tradeanalysis.strategy.live;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.strategy.engine.TrailingStopLoss;
import com.tradeanalysis.strategy.engine.TrailingStopLoss.Direction;
import com.tradeanalysis.strategy.engine.TrailingStopLoss.UpdateOutcome;
import com.tradeanalysis.strategy.model.NiftyPositionState;
import com.tradeanalysis.strategy.model.NiftyPositionState.Status;
import com.tradeanalysis.strategy.repository.NiftyPositionStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Stop-loss lifecycle of one strategy's live/paper position: trails the stop on completed 1-minute
 * candles, triggers the exit on spot ticks, and verifies the exit fill. Every change is persisted
 * before acting so a restart neither loses the trailed stop nor sends a second exit.
 *
 * Feed it from the market-data layer:
 *   {@link #onCandleClosed} once per completed 1-min NIFTY spot candle,
 *   {@link #onSpotTick}     for each NIFTY spot tick,
 *   {@link #verifyExit}     periodically / on order-update postbacks while an exit is pending,
 *   {@link #restore}        on startup and after every WebSocket reconnect.
 *
 * Not thread-safe; drive it from a single event thread.
 */
public class NiftyStopLossMonitor {

    private static final Logger log = LoggerFactory.getLogger(NiftyStopLossMonitor.class);
    private static final EnumSet<Status> ACTIVE = EnumSet.of(Status.OPEN, Status.EXIT_PENDING);

    private final Long strategyId;
    private final NiftyPositionStateRepository repo;
    private final CandleSource candles;
    private final ExitOrderGateway orders;

    private NiftyPositionState position;
    private TrailingStopLoss stop;

    public NiftyStopLossMonitor(Long strategyId, NiftyPositionStateRepository repo,
                                CandleSource candles, ExitOrderGateway orders) {
        this.strategyId = strategyId;
        this.repo = repo;
        this.candles = candles;
        this.orders = orders;
    }

    /**
     * Registers a newly entered position (entry already verified as filled) with its midpoint stop.
     * The state's lastProcessedCandle must be the start of the 5-minute breakout candle.
     */
    public void open(NiftyPositionState newPosition) {
        if (position != null) throw new IllegalStateException("A position is already active: " + position.getId());
        position = repo.save(newPosition);
        stop = TrailingStopLoss.open(Direction.valueOf(position.getDirection()),
                position.getReferenceHigh(), position.getReferenceLow(), position.getLastProcessedCandle());
        persistStop(); // from here on lastProcessedCandle tracks 1-minute candles
    }

    /** Reloads the active position with its last persisted stop, then replays candles missed while down. */
    public void restore(Instant now) {
        Optional<NiftyPositionState> active = repo.findFirstByStrategyIdAndStatusIn(strategyId, ACTIVE);
        if (active.isEmpty()) { position = null; stop = null; return; }
        position = active.get();
        stop = TrailingStopLoss.restore(Direction.valueOf(position.getDirection()),
                position.getCurrentStopLoss(), position.getLastProcessedCandle());
        log.info("SL_RESTORE strategyId={} symbol={} status={} sl={} lastCandle={}", strategyId,
                position.getTradingSymbol(), position.getStatus(), stop.currentStop(), stop.lastProcessedCandle());
        if (position.getStatus() == Status.EXIT_PENDING) {
            verifyExit();
        } else {
            recoverCandles(now);
        }
    }

    public void onCandleClosed(Candle candle, Instant now) {
        if (position == null || position.getStatus() != Status.OPEN) return;
        UpdateOutcome outcome = stop.onCandle(candle, now);
        if (outcome == UpdateOutcome.MISSING_CANDLES) {
            recoverCandles(now);
        } else if (outcome == UpdateOutcome.UPDATED || outcome == UpdateOutcome.UNCHANGED) {
            persistStop();
        }
    }

    /** Checks a NIFTY spot tick against the stop; places at most one exit order per position. */
    public void onSpotTick(double spot, Instant now) {
        if (position == null || position.getStatus() != Status.OPEN || !stop.isHit(spot)) return;

        alert("SL_TRIGGERED symbol={} direction={} spot={} sl={} at={}",
                position.getTradingSymbol(), position.getDirection(), spot, stop.currentStop(), now);
        // Persist the intent first: if we crash after the broker accepts the order, restore() sees
        // EXIT_PENDING and verifies instead of sending a second exit.
        position.exitRequested();
        position = repo.save(position);

        int remaining = position.getQuantity() - position.getExitFilledQuantity();
        try {
            String orderId = orders.placeExit(position, remaining);
            position.exitPlaced(orderId, remaining);
            position = repo.save(position);
        } catch (RuntimeException e) {
            // Unknown outcome (timeout etc.): the order may exist at the broker. Stay EXIT_PENDING and
            // do not retry blindly; needs reconciliation against the broker order book.
            alert("EXIT_ORDER_UNCERTAIN symbol={} error={} — reconcile with broker order book",
                    position.getTradingSymbol(), e.getMessage());
            return;
        }
        verifyExit();
    }

    /** Confirms the exit against the broker's reported fills; never treats an order id as a fill. */
    public void verifyExit() {
        if (position == null || position.getStatus() != Status.EXIT_PENDING) return;
        if (position.getExitOrderId() == null) {
            alert("EXIT_ORDER_UNCERTAIN symbol={} — exit requested but no order id recorded; reconcile manually",
                    position.getTradingSymbol());
            return;
        }
        ExitOrderGateway.OrderUpdate update;
        try {
            update = orders.status(position.getExitOrderId());
        } catch (RuntimeException e) {
            log.warn("EXIT_STATUS_UNAVAILABLE orderId={} error={}", position.getExitOrderId(), e.getMessage());
            return;
        }
        // Fills from earlier (rejected/cancelled) exit orders for this position.
        int filledBefore = position.getQuantity() - position.getExitOrderQuantity();
        switch (update.status()) {
            case COMPLETE -> {
                position.exitProgress("COMPLETE", position.getQuantity());
                position.closed();
                log.info("EXIT_FILLED symbol={} qty={} orderId={} finalSl={}",
                        position.getTradingSymbol(), position.getQuantity(), position.getExitOrderId(), stop.currentStop());
            }
            case OPEN -> {
                position.exitProgress("OPEN", filledBefore + update.filledQuantity());
                log.info("EXIT_PARTIAL symbol={} filled={}/{}", position.getTradingSymbol(),
                        position.getExitFilledQuantity(), position.getQuantity());
            }
            case REJECTED, CANCELLED -> {
                // Keep any quantity that did fill; the next stop-hit tick re-sends for the remainder.
                position.exitProgress(update.status().name(), filledBefore + update.filledQuantity());
                position.exitFailed(update.status().name());
                alert("EXIT_ORDER_FAILED symbol={} status={} message={} remainingQty={}",
                        position.getTradingSymbol(), update.status(), update.message(),
                        position.getQuantity() - position.getExitFilledQuantity());
            }
        }
        position = repo.save(position);
        if (position.getStatus() == Status.CLOSED) { position = null; stop = null; }
    }

    private void recoverCandles(Instant now) {
        for (Candle c : candles.oneMinuteCandlesAfter(stop.lastProcessedCandle(), now)) {
            UpdateOutcome outcome = stop.onCandle(c, now);
            if (outcome == UpdateOutcome.MISSING_CANDLES) {
                alert("SL_RECOVERY_INCOMPLETE symbol={} lastCandle={} — candle source has a gap; stop not trailed",
                        position.getTradingSymbol(), stop.lastProcessedCandle());
                break;
            }
        }
        persistStop();
    }

    private void persistStop() {
        position.updateStop(stop.currentStop(), stop.lastProcessedCandle());
        position = repo.save(position);
    }

    private void alert(String format, Object... args) {
        log.error("ALERT " + format, args);
    }

    public Optional<NiftyPositionState> position() { return Optional.ofNullable(position); }
    public Optional<Double> currentStop()           { return Optional.ofNullable(stop).map(TrailingStopLoss::currentStop); }
}
