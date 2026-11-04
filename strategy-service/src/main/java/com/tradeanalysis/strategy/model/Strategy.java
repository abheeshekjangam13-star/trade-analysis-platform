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
}
