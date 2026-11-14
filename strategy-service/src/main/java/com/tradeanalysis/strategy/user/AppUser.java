package com.tradeanalysis.strategy.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** A person using the platform: logs in with a password; self sign-ups wait (PENDING) until an admin approves them. */
@Entity
@Table(name = "app_user")
public class AppUser {

    public enum Status { PENDING, ACTIVE, DISABLED, REJECTED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(unique = true)
    private String email;

    private String fullName;

    @JsonIgnore
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant lastSeenAt;
    private Instant passwordChangedAt;
    private Instant lastLoginAt;

    @Column(unique = true, length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan plan = Plan.FREE;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Plan requestedPlan;

    private Instant planExpiresAt;
    private Instant approvedAt;
    private Long approvedByUserId;

    @Column(length = 500)
    private String reviewNote;

    /** Paid at sign-up and waiting for approval: the plan and days start when an admin approves. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Plan prepaidPlan;
    private Integer prepaidDays;

    /** When the user accepted the Terms and Conditions, and which version (see {@link Terms}). */
    private Instant termsAcceptedAt;
    @Column(length = 20)
    private String termsVersion;

    /** The user's own maximum loss for a day across all their strategies (per mode), or null when not set. */
    private Double maxDayLoss;

    // May trade live while an admin restricts live trading to picked users (trading_control.live_restricted)
    @Column(nullable = false)
    private boolean liveAccess;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "app_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<AppRole> roles = new LinkedHashSet<>();

    protected AppUser() {}

    public AppUser(String username, String email, String fullName) {
        this.username = username; this.email = email; this.fullName = fullName;
    }

    public void seen(Instant at)            { this.lastSeenAt = at; }
    public void setStatus(Status status)    { this.status = status; }
    public void setEmail(String email)      { this.email = email; }
    public void setFullName(String name)    { this.fullName = name; }
    public void setRoles(Set<AppRole> roles){ this.roles = new LinkedHashSet<>(roles); }
    public void setPasswordHash(String hash){ this.passwordHash = hash; this.passwordChangedAt = Instant.now(); }
    public void loggedIn(Instant at)        { this.lastLoginAt = at; this.lastSeenAt = at; }
    @JsonIgnore public String getPasswordHash() { return passwordHash; }
    public boolean isPasswordSet()          { return passwordHash != null && !passwordHash.isBlank(); }
    public Instant getPasswordChangedAt()   { return passwordChangedAt; }
    public Instant getLastLoginAt()         { return lastLoginAt; }
    public boolean isAdmin()                { return roles.stream().anyMatch(r -> "ADMIN".equals(r.getName())); }

    public void setPhone(String phone)      { this.phone = phone; }
    public void requestPlan(Plan p)         { this.requestedPlan = p; }
    public void setPlan(Plan p, Instant expiresAt) { this.plan = p; this.planExpiresAt = p == Plan.FREE ? null : expiresAt; }
    public void approved(Long byUserId, Instant at) { this.approvedByUserId = byUserId; this.approvedAt = at; }
    public void setReviewNote(String note)  { this.reviewNote = note; }
    public void prepaid(Plan p, int days)   { this.prepaidPlan = p; this.prepaidDays = days; }
    public void clearPrepaid()              { this.prepaidPlan = null; this.prepaidDays = null; }
    public void acceptTerms(String version, Instant at) { this.termsVersion = version; this.termsAcceptedAt = at; }
    public void setMaxDayLoss(Double amount) { this.maxDayLoss = amount; }
    public Double getMaxDayLoss()           { return maxDayLoss; }
    public boolean isLiveAccess()           { return liveAccess; }
    public void setLiveAccess(boolean on)   { this.liveAccess = on; }

    /** A GOLD/PREMIUM sign-up that hasn't paid yet (it reaches the admin only after paying). */
    public boolean awaitingSignupPayment() {
        return status == Status.PENDING && requestedPlan != null && requestedPlan != Plan.FREE && prepaidPlan == null;
    }

    /** What the user may do now: admins everything; a paid plan whose expiry has passed counts as FREE. */
    public Plan effectivePlan(Instant now) {
        if (isAdmin()) return Plan.PREMIUM_PRO;   // the highest plan (admins also skip every plan limit)
        if (plan != Plan.FREE && planExpiresAt != null && !planExpiresAt.isAfter(now)) return Plan.FREE;
        return plan;
    }
    public Plan getEffectivePlan()          { return effectivePlan(Instant.now()); }

    public String getPhone()                { return phone; }
    public Plan getPlan()                   { return plan; }
    public Plan getRequestedPlan()          { return requestedPlan; }
    public Instant getPlanExpiresAt()       { return planExpiresAt; }
    public Instant getApprovedAt()          { return approvedAt; }
    public Long getApprovedByUserId()       { return approvedByUserId; }
    public String getReviewNote()           { return reviewNote; }
    public Plan getPrepaidPlan()            { return prepaidPlan; }
    public Integer getPrepaidDays()         { return prepaidDays; }
    public Instant getTermsAcceptedAt()     { return termsAcceptedAt; }
    public String getTermsVersion()         { return termsVersion; }

    public Long getId()             { return id; }
    public String getUsername()     { return username; }
    public String getEmail()        { return email; }
    public String getFullName()     { return fullName; }
    public Status getStatus()       { return status; }
    public Instant getCreatedAt()   { return createdAt; }
    public Instant getLastSeenAt()  { return lastSeenAt; }
    public Set<AppRole> getRoles()  { return roles; }
}
