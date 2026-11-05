package com.tradeanalysis.strategy.payment;

import java.util.Map;

/** The Razorpay calls the platform makes (an interface so tests need no network). */
public interface RazorpayApi {

    record Order(String id, long amount, String currency, String status) {}

    /** POST /v1/orders. Amount is in paise. */
    Order createOrder(long amountPaise, String currency, String receipt, Map<String, String> notes);
}
