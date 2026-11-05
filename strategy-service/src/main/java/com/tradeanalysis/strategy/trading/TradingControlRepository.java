package com.tradeanalysis.strategy.trading;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TradingControlRepository extends JpaRepository<TradingControl, Short> {
    default TradingControl get() { return findById((short) 1).orElseThrow(); }
}
