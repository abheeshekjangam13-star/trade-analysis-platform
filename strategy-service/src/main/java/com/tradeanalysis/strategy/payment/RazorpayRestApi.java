package com.tradeanalysis.strategy.payment;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Razorpay REST API over HTTPS with Basic auth (key id : key secret). */
@Component
public class RazorpayRestApi implements RazorpayApi {

    private final RestClient http;

    public RazorpayRestApi(RazorpayProperties props,
                           @Value("${razorpay.base-url:https://api.razorpay.com}") String baseUrl) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(10));
        f.setReadTimeout(Duration.ofSeconds(20));
        String basic = Base64.getEncoder().encodeToString((props.keyId() + ":" + props.keySecret()).getBytes(StandardCharsets.UTF_8));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic).build();
    }

    @Override
    public Order createOrder(long amountPaise, String currency, String receipt, Map<String, String> notes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountPaise);
        body.put("currency", currency);
        body.put("receipt", receipt);
        body.put("notes", notes);
        JsonNode r = http.post().uri("/v1/orders").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class);
        if (r == null || !r.hasNonNull("id")) throw new IllegalStateException("Razorpay returned no order id");
        return new Order(r.get("id").asText(), r.path("amount").asLong(), r.path("currency").asText(), r.path("status").asText());
    }
}
