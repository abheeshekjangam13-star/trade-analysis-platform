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

    /** The deleted ones among these strategies: their trades are shown to admins only. */
    @org.springframework.data.jpa.repository.Query("SELECT s.id FROM Strategy s WHERE s.id IN :ids AND s.deletedAt IS NOT NULL")
    java.util.Set<Long> findDeletedIds(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

    boolean existsByNameAndCreatedByUserIdAndIdNot(String name, Long createdByUserId, Long id);
}
