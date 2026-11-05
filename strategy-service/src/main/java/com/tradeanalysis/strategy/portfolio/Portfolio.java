package com.tradeanalysis.strategy.portfolio;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** A named group of strategies that are reported together (e.g. CE 56 + PE 55). */
@Entity
@Table(name = "portfolio")
public class Portfolio {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "portfolio_strategy", joinColumns = @JoinColumn(name = "portfolio_id"))
    @Column(name = "strategy_id", nullable = false)
    private Set<Long> strategyIds = new LinkedHashSet<>();

    private Long createdByUserId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Portfolio() {}

    public Portfolio(String name, String description, Set<Long> strategyIds, Long createdByUserId) {
        this.name = name; this.description = description;
        this.strategyIds = new LinkedHashSet<>(strategyIds); this.createdByUserId = createdByUserId;
    }

    public Long getId()               { return id; }
    public String getName()           { return name; }
    public String getDescription()    { return description; }
    public Set<Long> getStrategyIds() { return strategyIds; }
    public Long getCreatedByUserId()  { return createdByUserId; }
    public Instant getCreatedAt()     { return createdAt; }
}
