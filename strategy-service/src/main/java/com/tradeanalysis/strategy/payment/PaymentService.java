package com.tradeanalysis.strategy.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.notify.EmailService;
import com.tradeanalysis.strategy.user.AppUser;
import com.tradeanalysis.strategy.user.CurrentUser;
import com.tradeanalysis.strategy.user.Plan;
import com.tradeanalysis.strategy.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One-time Razorpay payments for a month of GOLD or PREMIUM. The server sets the amount from the plan price, so the
 * browser can't change it. A payment counts only after its signature checks out (Checkout callback or webhook), and
 * each order activates the plan once: a renewal extends from the current expiry.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    static final String CURRENCY = "INR";

    private final RazorpayProperties props;
    private final RazorpayApi razorpay;
    private final PaymentOrderRepository orders;
    private final UserService users;
    private final EmailService emails;
    private final ObjectMapper json;
    private final Clock clock;

    public PaymentService(RazorpayProperties props, RazorpayApi razorpay, PaymentOrderRepository orders, UserService users,
                          EmailService emails, ObjectMapper json, Clock clock) {
        this.props = props; this.razorpay = razorpay; this.orders = orders; this.users = users;
        this.emails = emails; this.json = json; this.clock = clock;
    }

    public record Checkout(Long id, String orderId, long amountPaise, String currency, String keyId, Plan plan, int days,
                           String name, String email, String phone, String description) {}

    public record Outcome(PaymentOrder.Status status, Plan plan, Instant validUntil, String message) {}

    /** Creates a Razorpay order for the logged-in user; the UI opens Checkout with it. */
    @Transactional
    public Checkout createOrder(Plan plan) {
        requireEnabled();
        AppUser me = me();
        Instant now = clock.instant();
        if (plan == null || plan == Plan.FREE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a paid plan: GOLD or PREMIUM");
        if (me.isAdmin()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Admins already have every feature");
        Plan current = me.effectivePlan(now);
        if (current != Plan.FREE && current.ordinal() > plan.ordinal())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have " + current
                    + (me.getPlanExpiresAt() == null ? "" : " until " + me.getPlanExpiresAt()) + "; it includes " + plan);
        if (current == plan && me.getPlanExpiresAt() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your " + plan + " plan has no expiry; nothing to pay");

        return newOrder(me, plan);
    }

    /**
     * A GOLD/PREMIUM sign-up that hasn't paid yet (no login: the caller has checked the password or just created the
     * account). Checkout opens with this order; once paid, the sign-up goes to the admin.
     */
    @Transactional
    public Checkout signupOrder(Long userId) {
        requireEnabled();
        AppUser u = users.get(userId);
        if (!u.awaitingSignupPayment())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This sign-up is not waiting for a payment");
        return newOrder(u, u.getRequestedPlan());
    }

    private Checkout newOrder(AppUser me, Plan plan) {
        Instant now = clock.instant();
        long amount = plan.pricePerMonth() * 100L;
        String receipt = "u" + me.getId() + "-" + plan + "-" + now.getEpochSecond();
        RazorpayApi.Order o;
        try {
            o = razorpay.createOrder(amount, CURRENCY, receipt,
                    Map.of("userId", String.valueOf(me.getId()), "username", me.getUsername(), "plan", plan.name()));
        } catch (RuntimeException e) {
            log.error("PAYMENT order create failed user={} plan={}: {}", me.getUsername(), plan, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't reach Razorpay; try again in a minute");
        }
        if (o.amount() != amount)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Razorpay created an order for the wrong amount");
        PaymentOrder saved = orders.save(new PaymentOrder(me.getId(), plan, props.planDays(), amount, CURRENCY, o.id()));
        log.info("PAYMENT order created id={} rzp={} user={} plan={} amount={}", saved.getId(), o.id(), me.getUsername(), plan, amount);
        return new Checkout(saved.getId(), o.id(), amount, CURRENCY, props.keyId(), plan, props.planDays(),
                me.getFullName() == null ? me.getUsername() : me.getFullName(), me.getEmail(), me.getPhone(),
                plan + " plan, " + props.planDays() + " days");
    }

    /** Checkout success callback: checks the signature, then activates the plan (idempotent). */
    @Transactional
    public Outcome verifyCheckout(String orderId, String paymentId, String signature) {
        requireEnabled();
        if (orderId == null || paymentId == null || signature == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderId, paymentId and signature are required");
        PaymentOrder o = orders.lockByRazorpayOrderId(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown order: " + orderId));
        if (!CurrentUser.admin() && !o.getUserId().equals(CurrentUser.id().orElse(null)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown order: " + orderId);
        if (!RazorpaySignature.checkoutValid(props.keySecret(), orderId, paymentId, signature)) {
            log.warn("PAYMENT bad checkout signature order={} payment={}", orderId, paymentId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment signature is invalid");
        }
        return markPaid(o, paymentId);
    }

    /**
     * Checkout success for a sign-up (no login yet). The Razorpay signature proves the payment, and only orders of
     * PENDING sign-ups are accepted here.
     */
    @Transactional
    public Outcome verifySignupCheckout(String orderId, String paymentId, String signature) {
        requireEnabled();
        if (orderId == null || paymentId == null || signature == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderId, paymentId and signature are required");
        PaymentOrder o = orders.lockByRazorpayOrderId(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown order: " + orderId));
        if (users.get(o.getUserId()).getStatus() != AppUser.Status.PENDING && o.getStatus() != PaymentOrder.Status.PAID)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown order: " + orderId);
        if (!RazorpaySignature.checkoutValid(props.keySecret(), orderId, paymentId, signature)) {
            log.warn("PAYMENT bad sign-up checkout signature order={} payment={}", orderId, paymentId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment signature is invalid");
        }
        return markPaid(o, paymentId);
    }

    /**
     * Webhook from Razorpay (payment.captured, order.paid, payment.failed). The caller has already checked the
     * signature. Unknown orders and other events are ignored so Razorpay stops retrying.
     */
    @Transactional
    public void handleWebhook(String rawBody) {
        JsonNode root;
        try { root = json.readTree(rawBody); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not JSON"); }
        String event = root.path("event").asText();
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String orderId = payment.path("order_id").asText(root.path("payload").path("order").path("entity").path("id").asText(""));
        String paymentId = payment.path("id").asText(null);
        if (orderId.isEmpty()) { log.info("PAYMENT webhook {} without order id: ignored", event); return; }
        PaymentOrder o = orders.lockByRazorpayOrderId(orderId).orElse(null);
        if (o == null) { log.warn("PAYMENT webhook {} for unknown order {}: ignored", event, orderId); return; }

        switch (event) {
            case "payment.captured", "order.paid" -> {
                long amount = payment.path("amount").asLong(-1);
                if (amount != o.getAmountPaise() || !CURRENCY.equalsIgnoreCase(payment.path("currency").asText(CURRENCY))) {
                    log.error("PAYMENT webhook amount mismatch order={} expected={} got={}", orderId, o.getAmountPaise(), amount);
                    o.failed(paymentId, "Amount mismatch: expected " + o.getAmountPaise() + " paise, got " + amount);
                    return;
                }
                markPaid(o, paymentId);
            }
            case "payment.failed" -> {
                if (o.getStatus() == PaymentOrder.Status.PAID) { log.info("PAYMENT failed event for paid order {}: ignored", orderId); return; }
                boolean first = o.getStatus() != PaymentOrder.Status.FAILED;
                String reason = payment.path("error_description").asText("Payment failed");
                o.failed(paymentId, reason);
                log.info("PAYMENT failed order={} payment={}", orderId, paymentId);
                if (first) emails.paymentFailed(users.get(o.getUserId()), o.getPlan(), o.getAmountPaise(), reason);
            }
            default -> log.info("PAYMENT webhook {} order={}: ignored", event, orderId);
        }
    }

    @Transactional(readOnly = true)
    public List<PaymentOrder> history(boolean all) {
        if (all) { users.requireAdmin(); return orders.findAllByOrderByCreatedAtDesc(); }
        return orders.findByUserIdOrderByCreatedAtDesc(me().getId());
    }

    public Map<String, Object> config() {
        return Map.of("enabled", props.enabled(), "keyId", props.enabled() ? props.keyId() : "",
                "planDays", props.planDays(), "currency", CURRENCY);
    }

    private Outcome markPaid(PaymentOrder o, String paymentId) {
        if (o.getStatus() == PaymentOrder.Status.PAID)
            return new Outcome(o.getStatus(), o.getPlan(), o.getPlanValidUntil(), "Already activated");
        Instant now = clock.instant();
        AppUser u = users.get(o.getUserId());
        if (u.getStatus() == AppUser.Status.PENDING) {   // paid at sign-up: the days start when the admin approves
            o.paid(paymentId, now, null);
            users.signupPaid(u.getId(), o.getPlan(), o.getDays(), o.getAmountPaise(), paymentId);
            log.info("PAYMENT paid at sign-up order={} payment={} user={} plan={}", o.getRazorpayOrderId(), paymentId,
                    u.getUsername(), o.getPlan());
            return new Outcome(PaymentOrder.Status.PAID, o.getPlan(), null,
                    "Payment received. Your sign-up is now with an admin for approval; your " + o.getPlan()
                    + " plan starts when it is approved.");
        }
        // Renewing the same plan before it ends extends it; anything else starts today.
        Instant base = u.getPlan() == o.getPlan() && u.getPlanExpiresAt() != null && u.getPlanExpiresAt().isAfter(now)
                ? u.getPlanExpiresAt() : now;
        Instant until = base.plus(Duration.ofDays(o.getDays()));
        users.activatePaidPlan(u.getId(), o.getPlan(), until);
        o.paid(paymentId, now, until);
        emails.paymentReceived(u, o.getPlan(), o.getAmountPaise(), paymentId, until);
        log.info("PAYMENT paid order={} payment={} user={} plan={} until={}", o.getRazorpayOrderId(), paymentId,
                u.getUsername(), o.getPlan(), until);
        return new Outcome(PaymentOrder.Status.PAID, o.getPlan(), until, o.getPlan() + " is active until " + until);
    }

    private AppUser me() {
        return users.get(CurrentUser.id().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Log in first")));
    }

    private void requireEnabled() {
        if (!props.enabled())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Online payments are not set up yet; ask an admin");
    }

    boolean webhookValid(String rawBody, String signature) {
        return props.webhookEnabled() && RazorpaySignature.webhookValid(props.webhookSecret(), rawBody, signature);
    }
}
