package com.tradeanalysis.strategy.share;

import jakarta.persistence.*;

import java.time.Instant;

/** An admin's invitation to a user to take a copy of a strategy; accepting creates the user's own copy. */
@Entity
@Table(name = "strategy_share")
public class StrategyShare {

    public enum Status { PENDING, ACCEPTED, DECLINED, REVOKED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long strategyId;
    @Column(nullable = false) private Long sharedByUserId;
    @Column(nullable = false) private Long sharedWithUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(length = 500) private String note;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    private Instant respondedAt;
    private Long createdStrategyId;

    protected StrategyShare() {}

    public StrategyShare(Long strategyId, Long sharedByUserId, Long sharedWithUserId, String note) {
        this.strategyId = strategyId; this.sharedByUserId = sharedByUserId; this.sharedWithUserId = sharedWithUserId; this.note = note;
    }

    public void accepted(Long strategyId, Instant at) { this.status = Status.ACCEPTED; this.createdStrategyId = strategyId; this.respondedAt = at; }
    public void declined(Instant at) { this.status = Status.DECLINED; this.respondedAt = at; }
    public void revoked(Instant at)  { this.status = Status.REVOKED; this.respondedAt = at; }

    public Long getId()                { return id; }
    public Long getStrategyId()        { return strategyId; }
    public Long getSharedByUserId()    { return sharedByUserId; }
    public Long getSharedWithUserId()  { return sharedWithUserId; }
    public Status getStatus()          { return status; }
    public String getNote()            { return note; }
    public Instant getCreatedAt()      { return createdAt; }
    public Instant getRespondedAt()    { return respondedAt; }
    public Long getCreatedStrategyId() { return createdStrategyId; }
}
