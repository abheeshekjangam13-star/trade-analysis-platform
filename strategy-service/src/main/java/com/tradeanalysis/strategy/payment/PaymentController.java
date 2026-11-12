package com.tradeanalysis.strategy.payment;

import com.tradeanalysis.strategy.user.Plan;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments", description = "Pay for GOLD or PREMIUM with Razorpay (one month at a time)")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    public record OrderRequest(Plan plan) {}
    public record CreditsRequest(Integer amount) {}
    public record VerifyRequest(String orderId, String paymentId, String signature) {}

    private final PaymentService payments;

    public PaymentController(PaymentService payments) { this.payments = payments; }

    @GetMapping("/config")
    @Operation(summary = "Whether online payments are on, and the public Razorpay key for Checkout")
    public Map<String, Object> config() { return payments.config(); }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start paying for a plan: creates a Razorpay order to open Checkout with")
    public PaymentService.Checkout create(@RequestBody OrderRequest body) {
        return payments.createOrder(body == null ? null : body.plan());
    }

    @PostMapping("/credits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start adding credits for live orders (₹100–₹50,000): creates a Razorpay order to open Checkout with")
    public PaymentService.Checkout credits(@RequestBody CreditsRequest body) {
        return payments.createCreditOrder(body == null ? null : body.amount());
    }

    @PostMapping("/verify")
    @Operation(summary = "After Checkout succeeds: check Razorpay's signature and activate the plan")
    public PaymentService.Outcome verify(@RequestBody VerifyRequest body) {
        return payments.verifyCheckout(body.orderId(), body.paymentId(), body.signature());
    }

    @GetMapping
    @Operation(summary = "Your payments, newest first (ADMIN: ?all=true for everyone's)")
    public List<PaymentOrder> history(@RequestParam(defaultValue = "false") boolean all) { return payments.history(all); }

    /** Razorpay webhook (no login): accepted only with a valid X-Razorpay-Signature over the raw body. */
    @PostMapping("/webhook")
    @Operation(summary = "Razorpay webhook (payment.captured, order.paid, payment.failed); needs X-Razorpay-Signature")
    public ResponseEntity<Void> webhook(@RequestBody String rawBody,
                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        if (!payments.webhookValid(rawBody, signature)) {
            log.warn("PAYMENT webhook rejected: bad or missing signature");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        payments.handleWebhook(rawBody);
        return ResponseEntity.ok().build();
    }
}
