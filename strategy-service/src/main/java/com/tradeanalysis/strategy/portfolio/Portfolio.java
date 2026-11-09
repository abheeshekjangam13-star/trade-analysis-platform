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

    /** Set when the portfolio was deleted: it is hidden everywhere, but the row and its reports are kept. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Instant deletedAt;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long deletedByUserId;

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
    public void setName(String name)  { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    /** Replaces the strategies in place (the collection is Hibernate-managed). */
    public void setStrategyIds(java.util.Collection<Long> ids) { strategyIds.clear(); strategyIds.addAll(ids); }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isDeleted() { return deletedAt != null; }
    public Instant getDeletedAt() { return deletedAt; }
    public Long getDeletedByUserId() { return deletedByUserId; }

    /** Soft delete: hides the portfolio and frees its name (names are unique per owner). */
    public void softDelete(Long byUserId, Instant at) {
        this.deletedAt = at;
        this.deletedByUserId = byUserId;
        String suffix = " [deleted " + id + "]";
        this.name = (name.length() + suffix.length() > 255 ? name.substring(0, 255 - suffix.length()) : name) + suffix;
    }
}
