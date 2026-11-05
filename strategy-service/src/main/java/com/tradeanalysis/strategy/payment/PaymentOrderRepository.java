package com.tradeanalysis.strategy.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    /** Locks the order so the Checkout callback and the webhook can't both activate it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentOrder p WHERE p.razorpayOrderId = :orderId")
    Optional<PaymentOrder> lockByRazorpayOrderId(@Param("orderId") String orderId);

    List<PaymentOrder> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<PaymentOrder> findAllByOrderByCreatedAtDesc();
}
