package com.tradeanalysis.strategy.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Razorpay keys from the environment. Payments are off until RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET are set. */
@Component
public class RazorpayProperties {

    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final int planDays;

    public RazorpayProperties(@Value("${razorpay.key-id:}") String keyId,
                              @Value("${razorpay.key-secret:}") String keySecret,
                              @Value("${razorpay.webhook-secret:}") String webhookSecret,
                              @Value("${razorpay.plan-days:30}") int planDays) {
        this.keyId = keyId.trim(); this.keySecret = keySecret.trim(); this.webhookSecret = webhookSecret.trim();
        this.planDays = planDays;
    }

    public boolean enabled()        { return !keyId.isEmpty() && !keySecret.isEmpty(); }
    public boolean webhookEnabled() { return !webhookSecret.isEmpty(); }
    public String keyId()           { return keyId; }
    String keySecret()              { return keySecret; }
    String webhookSecret()          { return webhookSecret; }
    public int planDays()           { return planDays; }
}
