package com.tradeanalysis.strategy.trading;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {
    List<TradeOrder> findByTradeDateOrderByCreatedAtDesc(LocalDate date);
    List<TradeOrder> findByTradeDateAndMode(LocalDate date, TradingMode mode);
    List<TradeOrder> findByModeAndStatus(TradingMode mode, TradeOrder.Status status);
    long countByTradeDateAndModeAndStatusIn(LocalDate date, TradingMode mode, Collection<TradeOrder.Status> statuses);
    long countByTradeDateAndModeAndUserIdAndStatusIn(LocalDate date, TradingMode mode, Long userId, Collection<TradeOrder.Status> statuses);
    List<TradeOrder> findByTradeDateAndUserIdOrderByCreatedAtDesc(LocalDate date, Long userId);
}
