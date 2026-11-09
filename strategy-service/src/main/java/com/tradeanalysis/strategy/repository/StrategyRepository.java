package com.tradeanalysis.strategy.repository;

import com.tradeanalysis.strategy.model.Strategy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StrategyRepository extends JpaRepository<Strategy, Long> {
    Optional<Strategy> findByName(String name);
    boolean existsByNameAndCreatedByUserId(String name, Long createdByUserId);

    long countByCreatedByUserId(Long createdByUserId);

    /** Strategies the user owns now (deleted ones don't count towards the plan limit). */
    long countByCreatedByUserIdAndDeletedAtIsNull(Long createdByUserId);

    /** How many strategies this user has deleted (the Free plan allows a few). */
    long countByDeletedByUserId(Long deletedByUserId);

    java.util.List<Strategy> findByDeletedAtIsNull();

    boolean existsByNameAndCreatedByUserIdAndIdNot(String name, Long createdByUserId, Long id);
}
