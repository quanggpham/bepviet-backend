package com.example.demo.order;

import com.example.demo.dto.request.CartItemRequest;
import com.example.demo.dto.request.CheckoutRequest;
import com.example.demo.dto.response.OrderResponse;
import com.example.demo.entity.Coupon;
import com.example.demo.entity.Product;
import com.example.demo.entity.User;
import com.example.demo.enums.DiscountType;
import com.example.demo.enums.OrderStatus;
import com.example.demo.enums.PaymentMethod;
import com.example.demo.enums.PaymentStatus;
import com.example.demo.enums.PromotionType;
import com.example.demo.enums.Role;
import com.example.demo.repository.CouponRepository;
import com.example.demo.repository.CouponUsageRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.PaymentRepository;
import com.example.demo.service.OrderService;
import com.example.demo.support.IntegrationTestBase;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class OrderCancellationConcurrencyTest extends IntegrationTestBase {

    @Autowired
    OrderService orderService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    CouponRepository couponRepository;

    @Autowired
    CouponUsageRepository couponUsageRepository;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @RepeatedTest(30)
    void concurrentCancelsRestockAndReleaseCouponOnce() throws Exception {
        Product product = createProduct(10, new BigDecimal("100000"));
        Coupon coupon = createCoupon(5);
        User buyer = createUser(Role.USER);
        OrderResponse order = orderService.createOrder(buyer.getId(), checkout(PaymentMethod.COD, coupon.getCode(), product, 3));

        List<Callable<?>> cancels = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            cancels.add(() -> { orderService.cancelOrder(order.getId(), buyer.getId()); return null; });
        }
        int succeeded = runConcurrently(cancels);

        long stock = stockOf(product);
        int usedCount = couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount();
        long usages = usagesOf(coupon);
        System.out.printf("[LIFECYCLE] double-cancel: cancels=10 succeeded=%d stock=%d(expected 10) couponUsedCount=%d usages=%d%n",
                succeeded, stock, usedCount, usages);

        assertThat(succeeded).isEqualTo(1);
        assertThat(stock).isEqualTo(10);
        assertThat(usedCount).isZero();
        assertThat(usages).isZero();
    }

    @Test
    void cancelledOrderGivesTheCouponBack() throws Exception {
        Product product = createProduct(100, new BigDecimal("100000"));
        Coupon coupon = createCoupon(1);
        User first = createUser(Role.USER);
        User second = createUser(Role.USER);

        OrderResponse order = orderService.createOrder(first.getId(), checkout(PaymentMethod.COD, coupon.getCode(), product, 1));
        orderService.cancelOrder(order.getId(), first.getId());

        // the single use is available again, both to another user and to the original one
        orderService.createOrder(second.getId(), checkout(PaymentMethod.COD, coupon.getCode(), product, 1));
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount()).isEqualTo(1);

        Coupon another = createCoupon(1);
        OrderResponse again = orderService.createOrder(first.getId(), checkout(PaymentMethod.COD, another.getCode(), product, 1));
        orderService.cancelOrder(again.getId(), first.getId());
        orderService.createOrder(first.getId(), checkout(PaymentMethod.COD, another.getCode(), product, 1));
    }

    @RepeatedTest(30)
    void userCancelRacingStripePaymentNeverLeavesPaidOrderCancelled() throws Exception {
        Product product = createProduct(10, new BigDecimal("50000"));
        User buyer = createUser(Role.USER);
        OrderResponse order = orderService.createOrder(buyer.getId(), checkout(PaymentMethod.STRIPE, null, product, 2));
        String payload = """
                {"id":"evt_%s","object":"event","type":"checkout.session.completed","data":{"object":{
                 "id":"cs_%s","object":"checkout.session","payment_status":"paid","amount_total":100000,
                 "metadata":{"orderId":"%d"}}}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), order.getId());
        String signature = stripeSignature(payload);

        List<Callable<?>> tasks = List.of(
                () -> { orderService.cancelOrder(order.getId(), buyer.getId()); return null; },
                () -> mockMvc.perform(post("/api/v1/payments/stripe/webhook")
                        .header("Stripe-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn());
        runConcurrently(tasks, 20);

        OrderStatus status = orderRepository.findById(order.getId()).orElseThrow().getStatus();
        long stock = stockOf(product);
        boolean paid = paymentRepository.findAll().stream()
                .anyMatch(p -> p.getOrder().getId().equals(order.getId()) && p.getStatus() == PaymentStatus.COMPLETED);
        boolean consistent = (status == OrderStatus.CONFIRMED && stock == 8 && paid)
                || (status == OrderStatus.CANCELLED && stock == 10 && !paid);
        System.out.printf("[LIFECYCLE] cancel-vs-webhook: status=%s stock=%d paid=%s consistent=%s%n", status, stock, paid, consistent);

        assertThat(consistent).isTrue();
    }

    @RepeatedTest(30)
    void expiryJobRacingStripePaymentNeverLeavesPaidOrderCancelled() throws Exception {
        Product product = createProduct(10, new BigDecimal("50000"));
        User buyer = createUser(Role.USER);
        OrderResponse order = orderService.createOrder(buyer.getId(), checkout(PaymentMethod.STRIPE, null, product, 2));
        jdbcTemplate.update("UPDATE orders SET created_at = now() - interval '2 hours' WHERE id = ?", order.getId());
        String payload = """
                {"id":"evt_%s","object":"event","type":"checkout.session.completed","data":{"object":{
                 "id":"cs_%s","object":"checkout.session","payment_status":"paid","amount_total":100000,
                 "metadata":{"orderId":"%d"}}}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), order.getId());
        String signature = stripeSignature(payload);

        List<Callable<?>> tasks = List.of(
                () -> orderService.expireUnpaidOrder(order.getId(), LocalDateTime.now().minusMinutes(60)),
                () -> mockMvc.perform(post("/api/v1/payments/stripe/webhook")
                        .header("Stripe-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn());
        runConcurrently(tasks, 20);

        OrderStatus status = orderRepository.findById(order.getId()).orElseThrow().getStatus();
        long stock = stockOf(product);
        boolean paid = paymentRepository.findAll().stream()
                .anyMatch(p -> p.getOrder().getId().equals(order.getId()) && p.getStatus() == PaymentStatus.COMPLETED);
        boolean consistent = (status == OrderStatus.CONFIRMED && stock == 8 && paid)
                || (status == OrderStatus.CANCELLED && stock == 10 && !paid);
        System.out.printf("[LIFECYCLE] expiry-vs-webhook: status=%s stock=%d paid=%s consistent=%s%n", status, stock, paid, consistent);

        assertThat(consistent).isTrue();
    }

    // --- helpers ---

    private int runConcurrently(List<Callable<?>> tasks) throws Exception {
        return runConcurrently(tasks, 0);
    }

    /** With jitter > 0 each task waits a random 0..jitter ms after the start signal, so every interleaving occurs. */
    private int runConcurrently(List<Callable<?>> tasks, int jitterMs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (Callable<?> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                if (jitterMs > 0) {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(jitterMs + 1));
                }
                try {
                    task.call();
                    ok.incrementAndGet();
                } catch (Exception expectedForLosers) {
                    // "only pending orders can be cancelled" is the expected outcome for losers
                }
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return ok.get();
    }

    private Coupon createCoupon(int usageLimit) {
        return couponRepository.save(Coupon.builder()
                .code("C" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(new BigDecimal("10000"))
                .promotionType(PromotionType.ORDER)
                .usageLimit(usageLimit)
                .usedCount(0)
                .startDate(LocalDate.now().minusDays(1))
                .expirationDate(LocalDate.now().plusDays(30))
                .active(true)
                .build());
    }

    private CheckoutRequest checkout(PaymentMethod method, String couponCode, Product product, int qty) {
        CartItemRequest item = new CartItemRequest();
        item.setProductId(product.getId());
        item.setQuantity(qty);
        CheckoutRequest request = new CheckoutRequest();
        request.setFullName("Buyer");
        request.setPhoneNumber("0912345678");
        request.setShippingAddress("HN");
        request.setPaymentMethod(method);
        request.setItems(List.of(item));
        request.setCode(couponCode);
        return request;
    }

    private long stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStockQuantity();
    }

    private long usagesOf(Coupon coupon) {
        return couponUsageRepository.findAll().stream()
                .filter(cu -> cu.getCoupon().getId().equals(coupon.getId()))
                .count();
    }
}
