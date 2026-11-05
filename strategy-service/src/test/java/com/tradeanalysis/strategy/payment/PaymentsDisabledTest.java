package com.tradeanalysis.strategy.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Without Razorpay keys payments are off, and the webhook rejects everything. */
@SpringBootTest(properties = {"razorpay.key-id=", "razorpay.key-secret=", "razorpay.webhook-secret=",
        "spring.datasource.url=jdbc:h2:mem:payment-off-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@DirtiesContext
class PaymentsDisabledTest {

    @Autowired MockMvc mvc;

    @Test
    void paymentsAreOffWithoutKeys() throws Exception {
        mvc.perform(get("/api/payments/config").header("X-User", "ravi")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/payments/orders").header("X-User", "ravi").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"GOLD\"}")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("X-Razorpay-Signature", "x")).andExpect(status().isBadRequest());
    }
}
