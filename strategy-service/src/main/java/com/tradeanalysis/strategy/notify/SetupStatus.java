package com.tradeanalysis.strategy.notify;

import com.tradeanalysis.strategy.payment.RazorpayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether email and Razorpay are set up on this server, and what is missing. Logged once at startup (so a GCP deploy
 * shows it in {@code docker compose logs strategy-service}) and shown to admins in the UI. Never reveals secrets.
 */
@Component
public class SetupStatus {

    private static final Logger log = LoggerFactory.getLogger(SetupStatus.class);

    private final EmailDispatcher mail;
    private final RazorpayProperties razorpay;
    private final String appUrl;

    public SetupStatus(EmailDispatcher mail, RazorpayProperties razorpay,
                       @Value("${notifications.app-url:http://localhost:5173}") String appUrl) {
        this.mail = mail; this.razorpay = razorpay; this.appUrl = appUrl;
    }

    public Map<String, Object> status() {
        List<String> problems = new ArrayList<>();
        if (!mail.smtpConfigured()) problems.add("Email is off: set MAIL_HOST, MAIL_USERNAME and MAIL_PASSWORD (emails are only logged until then)");
        else if (mail.from().isBlank()) problems.add("Email has no sender: set MAIL_FROM or MAIL_USERNAME");
        if (!razorpay.enabled()) problems.add("Online payments are off: set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET");
        else if (!razorpay.webhookEnabled()) problems.add("Razorpay webhook is off: set RAZORPAY_WEBHOOK_SECRET so payments are confirmed even if the browser closes");
        if (appUrl.contains("localhost")) problems.add("APP_URL is " + appUrl + ": links in emails won't work for users");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("emailConfigured", mail.smtpConfigured());
        m.put("emailFrom", mail.from());
        m.put("paymentsEnabled", razorpay.enabled());
        m.put("paymentsMode", !razorpay.enabled() ? "OFF" : razorpay.keyId().startsWith("rzp_live_") ? "LIVE" : "TEST");
        m.put("webhookConfigured", razorpay.webhookEnabled());
        m.put("webhookUrl", appUrl.replaceAll("/$", "") + "/api/payments/webhook");
        m.put("appUrl", appUrl);
        m.put("problems", problems);
        return m;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logAtStartup() {
        Map<String, Object> s = status();
        log.info("SETUP email={} from='{}' payments={} webhook={} appUrl={}", s.get("emailConfigured") , s.get("emailFrom"),
                s.get("paymentsMode"), s.get("webhookConfigured"), s.get("appUrl"));
        @SuppressWarnings("unchecked") List<String> problems = (List<String>) s.get("problems");
        problems.forEach(p -> log.warn("SETUP {}", p));
    }
}
