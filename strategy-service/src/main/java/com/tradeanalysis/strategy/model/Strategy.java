package com.tradeanalysis.strategy.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "strategy")
public class Strategy {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** E.g. "SMA_CROSSOVER", "RSI_THRESHOLD", "MACD_SIGNAL" */
    @Column(nullable = false)
    private String type;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "strategy_param", joinColumns = @JoinColumn(name = "strategy_id"))
    @MapKeyColumn(name = "param_key")
    @Column(name = "param_value")
    private Map<String, String> parameters;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** User who created the strategy (null for strategies created before users existed or without X-User). */
    private Long createdByUserId;

    /** Strategy this one was cloned from (null when created from scratch). */
    private Long clonedFromStrategyId;

    /** Set when the strategy was deleted: it is hidden everywhere, but the row and its history are kept. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Instant deletedAt;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long deletedByUserId;

    protected Strategy() {}

    public Strategy(String name, String description, String type, Map<String, String> parameters) {
        this.name = name; this.description = description;
        this.type = type; this.parameters = parameters;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getType() { return type; }
    public Map<String, String> getParameters() { return parameters; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long userId) { this.createdByUserId = userId; }
    public Long getClonedFromStrategyId() { return clonedFromStrategyId; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    /** Replaces the parameters in place (the collection is Hibernate-managed). */
    public void setParameters(Map<String, String> params) {
        if (this.parameters == null) this.parameters = new java.util.HashMap<>();
        this.parameters.clear();
        this.parameters.putAll(params);
    }
    public void setClonedFromStrategyId(Long id) { this.clonedFromStrategyId = id; }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isDeleted() { return deletedAt != null; }
    public Instant getDeletedAt() { return deletedAt; }
    public Long getDeletedByUserId() { return deletedByUserId; }

    /**
     * Soft delete: hides the strategy and frees its name (names are unique per owner), so the owner can reuse it.
     */
    public void softDelete(Long byUserId, Instant at) {
        this.deletedAt = at;
        this.deletedByUserId = byUserId;
        String suffix = " [deleted " + id + "]";
        this.name = (name.length() + suffix.length() > 255 ? name.substring(0, 255 - suffix.length()) : name) + suffix;
    }
}
