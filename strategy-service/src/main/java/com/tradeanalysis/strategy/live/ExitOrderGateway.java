package com.tradeanalysis.strategy.live;

import com.tradeanalysis.strategy.model.NiftyPositionState;

/**
 * Places and tracks the exit order for an open position. Only {@link PaperExitOrderGateway} exists;
 * a Kite-backed implementation (SELL MARKET on NFO via /orders/regular) has not been built yet.
 */
public interface ExitOrderGateway {

    enum OrderStatus { OPEN, COMPLETE, REJECTED, CANCELLED }

    record OrderUpdate(OrderStatus status, int filledQuantity, String message) {}

    /** Places a SELL for {@code quantity} of the position's option. Returns the broker order id. */
    String placeExit(NiftyPositionState position, int quantity);

    /** Current broker-side state of an order; must reflect actual fills, not just acceptance. */
    OrderUpdate status(String orderId);
}
