package com.example.demo.service;

import com.example.demo.enums.OrderStatus;
import com.example.demo.enums.PaymentMethod;
import com.example.demo.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Cancels Stripe orders that were never paid so their stock and coupon go back on sale.
 * Each order is expired in its own transaction under a row lock, so a payment webhook
 * arriving at the same moment either confirms the order first or finds it cancelled.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnpaidOrderExpiryJob {

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @Value("${app.order-expiry.ttl-minutes:60}")
    private long ttlMinutes;

    @Scheduled(
            fixedDelayString = "${app.order-expiry.fixed-delay-ms:60000}",
            initialDelayString = "${app.order-expiry.initial-delay-ms:${app.order-expiry.fixed-delay-ms:60000}}"
    )
    public void expireUnpaidStripeOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(ttlMinutes);
        int expired = 0;
        for (Long orderId : orderRepository.findIdsByStatusAndPaymentMethodCreatedBefore(
                OrderStatus.PENDING, PaymentMethod.STRIPE, cutoff)) {
            try {
                if (orderService.expireUnpaidOrder(orderId, cutoff)) {
                    expired++;
                }
            } catch (Exception ex) {
                log.warn("Khong the huy don qua han orderId={}: {}", orderId, ex.getMessage());
            }
        }
        if (expired > 0) {
            log.info("Da huy {} don Stripe qua han thanh toan (> {} phut)", expired, ttlMinutes);
        }
    }
}
