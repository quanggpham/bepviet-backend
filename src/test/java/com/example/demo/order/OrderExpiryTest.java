package com.example.demo.order;

import com.example.demo.dto.request.CartItemRequest;
import com.example.demo.dto.request.CheckoutRequest;
import com.example.demo.dto.response.OrderResponse;
import com.example.demo.entity.Product;
import com.example.demo.entity.User;
import com.example.demo.enums.OrderStatus;
import com.example.demo.enums.PaymentMethod;
import com.example.demo.enums.Role;
import com.example.demo.repository.OrderRepository;
import com.example.demo.service.OrderService;
import com.example.demo.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Unpaid Stripe orders must not hold stock forever. The expiry job runs every 500 ms in this context.
 */
@TestPropertySource(properties = {
        "app.order-expiry.ttl-minutes=60",
        "app.order-expiry.fixed-delay-ms=500"
})
class OrderExpiryTest extends IntegrationTestBase {

    @Autowired
    OrderService orderService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

    @Test
    void expiredUnpaidStripeOrderIsCancelledAndRestockedByTheJob() throws Exception {
        User buyer = createUser(Role.USER);
        Product expiredProduct = createProduct(10, new BigDecimal("50000"));
        Product codProduct = createProduct(10, new BigDecimal("50000"));
        Product freshProduct = createProduct(10, new BigDecimal("50000"));

        OrderResponse expired = orderService.createOrder(buyer.getId(), checkout(PaymentMethod.STRIPE, expiredProduct, 2));
        OrderResponse oldCod = orderService.createOrder(buyer.getId(), checkout(PaymentMethod.COD, codProduct, 2));
        OrderResponse fresh = orderService.createOrder(buyer.getId(), checkout(PaymentMethod.STRIPE, freshProduct, 2));
        ageOrder(expired.getId(), 120);
        ageOrder(oldCod.getId(), 120);

        boolean cancelled = waitForStatus(expired.getId(), OrderStatus.CANCELLED, 10_000);
        System.out.printf("[EXPIRY] job: expiredStripe=%s stock=%d | oldCod=%s | freshStripe=%s%n",
                statusOf(expired.getId()), stockOf(expiredProduct), statusOf(oldCod.getId()), statusOf(fresh.getId()));

        assertThat(cancelled).isTrue();
        assertThat(stockOf(expiredProduct)).isEqualTo(10);
        assertThat(statusOf(oldCod.getId())).isEqualTo(OrderStatus.PENDING);
        assertThat(stockOf(codProduct)).isEqualTo(8);
        assertThat(statusOf(fresh.getId())).isEqualTo(OrderStatus.PENDING);
        assertThat(stockOf(freshProduct)).isEqualTo(8);
    }

    @Test
    void checkoutSessionIsRefusedForCancelledOrder() throws Exception {
        User buyer = createUser(Role.USER);
        OrderResponse order = orderService.createOrder(buyer.getId(),
                checkout(PaymentMethod.STRIPE, createProduct(10, new BigDecimal("50000")), 1));
        orderService.cancelOrder(order.getId(), buyer.getId());

        int status = mockMvc.perform(post("/api/v1/payments/" + order.getId() + "/stripe/checkout")
                .header("Authorization", bearer(buyer))).andReturn().getResponse().getStatus();
        System.out.printf("[EXPIRY] checkout-for-cancelled: status=%d%n", status);

        assertThat(status).isEqualTo(400);
    }

    @Test
    void checkoutSessionIsRefusedWhenItCouldOutliveTheOrder() throws Exception {
        // Stripe sessions live at least 30 minutes; a 45-minute-old order expires at 60.
        User buyer = createUser(Role.USER);
        OrderResponse order = orderService.createOrder(buyer.getId(),
                checkout(PaymentMethod.STRIPE, createProduct(10, new BigDecimal("50000")), 1));
        ageOrder(order.getId(), 45);

        int status = mockMvc.perform(post("/api/v1/payments/" + order.getId() + "/stripe/checkout")
                .header("Authorization", bearer(buyer))).andReturn().getResponse().getStatus();
        System.out.printf("[EXPIRY] checkout-near-expiry: status=%d%n", status);

        assertThat(status).isEqualTo(400);
    }

    // --- helpers ---

    private void ageOrder(Long orderId, int minutes) {
        jdbcTemplate.update("UPDATE orders SET created_at = now() - make_interval(mins => ?) WHERE id = ?", minutes, orderId);
    }

    private boolean waitForStatus(Long orderId, OrderStatus expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (statusOf(orderId) == expected) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private CheckoutRequest checkout(PaymentMethod method, Product product, int qty) {
        CartItemRequest item = new CartItemRequest();
        item.setProductId(product.getId());
        item.setQuantity(qty);
        CheckoutRequest request = new CheckoutRequest();
        request.setFullName("Buyer");
        request.setPhoneNumber("0912345678");
        request.setShippingAddress("HN");
        request.setPaymentMethod(method);
        request.setItems(List.of(item));
        return request;
    }

    private OrderStatus statusOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private long stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStockQuantity();
    }
}
