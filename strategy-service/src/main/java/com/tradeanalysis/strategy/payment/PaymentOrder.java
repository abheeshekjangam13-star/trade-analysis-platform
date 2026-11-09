package com.tradeanalysis.strategy.payment;

import com.tradeanalysis.strategy.user.Plan;
import jakarta.persistence.*;

import java.time.Instant;

/** A Razorpay order for one plan period. CREATED until a verified payment marks it PAID (once). */
@Entity
@Table(name = "payment_order")
public class PaymentOrder {

    public enum Status { CREATED, PAID, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan plan;

    @Column(nullable = false)
    private int days;

    @Column(nullable = false)
    private long amountPaise;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, unique = true, length = 64)
    private String razorpayOrderId;

    @Column(length = 64)
    private String razorpayPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.CREATED;

    @Column(length = 500)
    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant paidAt;
    private Instant planValidUntil;

    protected PaymentOrder() {}

    public PaymentOrder(Long userId, Plan plan, int days, long amountPaise, String currency, String razorpayOrderId) {
        this.userId = userId; this.plan = plan; this.days = days; this.amountPaise = amountPaise;
        this.currency = currency; this.razorpayOrderId = razorpayOrderId;
    }

    void paid(String paymentId, Instant at, Instant validUntil) {
        status = Status.PAID; razorpayPaymentId = paymentId; paidAt = at; planValidUntil = validUntil; failureReason = null;
    }

    /** A sign-up payment's plan starts at approval: its valid-until date is the plan's end. */
    public void planStarts(Instant validUntil) { planValidUntil = validUntil; }

    void failed(String paymentId, String reason) {
        if (status == Status.PAID) return;
        status = Status.FAILED; razorpayPaymentId = paymentId;
        failureReason = reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason;
    }

    public Long getId()                 { return id; }
    public Long getUserId()             { return userId; }
    public Plan getPlan()               { return plan; }
    public int getDays()                { return days; }
    public long getAmountPaise()        { return amountPaise; }
    public String getCurrency()         { return currency; }
    public String getRazorpayOrderId()  { return razorpayOrderId; }
    public String getRazorpayPaymentId(){ return razorpayPaymentId; }
    public Status getStatus()           { return status; }
    public String getFailureReason()    { return failureReason; }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getPaidAt()          { return paidAt; }
    public Instant getPlanValidUntil()  { return planValidUntil; }
}
