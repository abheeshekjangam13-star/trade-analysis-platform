package com.tradeanalysis.strategy.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** HMAC-SHA256 checks Razorpay uses for the Checkout callback and for webhooks. */
final class RazorpaySignature {

    private RazorpaySignature() {}

    static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Checkout: signature = HMAC(order_id + "|" + payment_id, key_secret). */
    static boolean checkoutValid(String keySecret, String orderId, String paymentId, String signature) {
        return matches(hmacHex(keySecret, orderId + "|" + paymentId), signature);
    }

    /** Webhook: X-Razorpay-Signature = HMAC(raw body, webhook secret). */
    static boolean webhookValid(String webhookSecret, String rawBody, String signature) {
        return matches(hmacHex(webhookSecret, rawBody), signature);
    }

    private static boolean matches(String expected, String given) {
        if (given == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.trim().getBytes(StandardCharsets.UTF_8));
    }
}
