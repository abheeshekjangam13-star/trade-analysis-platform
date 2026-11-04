package com.tradeanalysis.strategy.repository;

import com.tradeanalysis.strategy.model.NiftyPositionState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface NiftyPositionStateRepository extends JpaRepository<NiftyPositionState, Long> {
    Optional<NiftyPositionState> findFirstByStrategyIdAndStatusIn(Long strategyId, Collection<NiftyPositionState.Status> statuses);
}
