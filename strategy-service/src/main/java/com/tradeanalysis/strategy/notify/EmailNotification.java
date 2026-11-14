package com.tradeanalysis.strategy.notify;

import jakarta.persistence.*;

import java.time.Instant;

/** One email in the outbox. PENDING until sent; FAILED rows are retried until {@link #MAX_ATTEMPTS}. */
@Entity
@Table(name = "email_notification")
public class EmailNotification {

    public enum Status { PENDING, SENT, FAILED, LOGGED }

    public enum Kind {
        SIGNUP_RECEIVED, SIGNUP_ADMIN_ALERT, ACCOUNT_APPROVED, ACCOUNT_REJECTED, ACCOUNT_CREATED, PAYMENT_RECEIVED,
        PAYMENT_FAILED, PLAN_CHANGED, PLAN_EXPIRING, PLAN_EXPIRED, PASSWORD_CHANGED, LIVE_ORDERS_LOW, CREDITS_ADDED, APP_INVITE, BETA_INVITE, TEST
    }

    public static final int MAX_ATTEMPTS = 5;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Column(nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Kind kind;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /** The HTML version (null for emails queued before HTML templates). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String htmlBody;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 1000)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant sentAt;

    protected EmailNotification() {}

    public EmailNotification(Long userId, String recipient, Kind kind, String subject, String body) {
        this.userId = userId; this.recipient = recipient; this.kind = kind; this.subject = subject; this.body = body;
    }

    public EmailNotification(Long userId, String recipient, Kind kind, String subject, String body, String htmlBody) {
        this(userId, recipient, kind, subject, body);
        this.htmlBody = htmlBody;
    }

    public void sent(Instant at)       { status = Status.SENT; sentAt = at; attempts++; lastError = null; }
    public void logged(Instant at)     { status = Status.LOGGED; sentAt = at; }
    public void failed(String error)   {
        attempts++;
        status = Status.FAILED;
        lastError = error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error;
    }
    /** Admin retry: queue again with a fresh attempt budget. */
    public void requeue()              { status = Status.PENDING; attempts = 0; lastError = null; }
    public boolean retryable()         { return status == Status.PENDING || status == Status.FAILED && attempts < MAX_ATTEMPTS; }

    public Long getId()          { return id; }
    public Long getUserId()      { return userId; }
    public String getRecipient() { return recipient; }
    public Kind getKind()        { return kind; }
    public String getSubject()   { return subject; }
    public String getBody()      { return body; }
    public String getHtmlBody()  { return htmlBody; }
    public Status getStatus()    { return status; }
    public int getAttempts()     { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt(){ return createdAt; }
    public Instant getSentAt()   { return sentAt; }
}
