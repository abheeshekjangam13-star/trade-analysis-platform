package com.tradeanalysis.strategy.live;

import com.tradeanalysis.strategy.model.NiftyPositionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Dry-run gateway: no broker call, every exit fills in full immediately. */
public class PaperExitOrderGateway implements ExitOrderGateway {

    private static final Logger log = LoggerFactory.getLogger(PaperExitOrderGateway.class);

    private final Map<String, Integer> orders = new ConcurrentHashMap<>();

    @Override
    public String placeExit(NiftyPositionState position, int quantity) {
        String id = "PAPER-" + UUID.randomUUID();
        orders.put(id, quantity);
        log.info("PAPER_EXIT orderId={} symbol={} qty={}", id, position.getTradingSymbol(), quantity);
        return id;
    }

    @Override
    public OrderUpdate status(String orderId) {
        Integer qty = orders.get(orderId);
        return qty == null
                ? new OrderUpdate(OrderStatus.REJECTED, 0, "unknown paper order")
                : new OrderUpdate(OrderStatus.COMPLETE, qty, "paper fill");
    }
}
