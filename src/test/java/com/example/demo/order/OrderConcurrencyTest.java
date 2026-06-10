package com.example.demo.order;

import com.example.demo.dto.request.CartItemRequest;
import com.example.demo.dto.request.CheckoutRequest;
import com.example.demo.dto.response.OrderResponse;
import com.example.demo.entity.Coupon;
import com.example.demo.entity.Product;
import com.example.demo.entity.User;
import com.example.demo.enums.DiscountType;
import com.example.demo.enums.PaymentMethod;
import com.example.demo.enums.PromotionType;
import com.example.demo.enums.Role;
import com.example.demo.repository.CouponRepository;
import com.example.demo.repository.CouponUsageRepository;
import com.example.demo.repository.OrderDetailRepository;
import com.example.demo.service.OrderService;
import com.example.demo.support.IntegrationTestBase;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires many checkouts at the same instant against a real PostgreSQL.
 * Each scenario prints a "[CONCURRENCY]" line so before/after numbers can be collected from the log.
 */
class OrderConcurrencyTest extends IntegrationTestBase {

    private static final int THREADS = 50;

    @Autowired
    OrderService orderService;

    @Autowired
    CouponRepository couponRepository;

    @Autowired
    CouponUsageRepository couponUsageRepository;

    @Autowired
    OrderDetailRepository orderDetailRepository;

    @RepeatedTest(10)
    void fiftyBuyersCannotOversellTenUnits() throws Exception {
        Product product = createProduct(10, new BigDecimal("10000"));
        List<User> buyers = createUsers(THREADS);

        int succeeded = runConcurrently(buyers.stream()
                .<Callable<?>>map(u -> () -> orderService.createOrder(u.getId(), checkout(null, item(product, 1))))
                .toList());

        long finalStock = stockOf(product);
        long sold = countSold(product);
        System.out.printf("[CONCURRENCY] stock: initial=10 threads=%d succeeded=%d soldUnits=%d finalStock=%d%n",
                THREADS, succeeded, sold, finalStock);

        assertThat(sold).isEqualTo(10);
        assertThat(succeeded).isEqualTo(10);
        assertThat(finalStock).isZero();
    }

    @RepeatedTest(10)
    void couponUsageLimitHoldsUnderConcurrency() throws Exception {
        Product product = createProduct(1_000, new BigDecimal("100000"));
        Coupon coupon = createCoupon(5);
        List<User> buyers = createUsers(THREADS);

        int succeeded = runConcurrently(buyers.stream()
                .<Callable<?>>map(u -> () -> orderService.createOrder(u.getId(), checkout(coupon.getCode(), item(product, 1))))
                .toList());

        long usages = couponUsageRepository.findAll().stream()
                .filter(cu -> cu.getCoupon().getId().equals(coupon.getId())).count();
        int usedCount = couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount();
        System.out.printf("[CONCURRENCY] coupon: limit=5 threads=%d succeeded=%d usages=%d usedCount=%d%n",
                THREADS, succeeded, usages, usedCount);

        assertThat(usages).isEqualTo(5);
        assertThat(usedCount).isEqualTo(5);
        assertThat(stockOf(product)).isEqualTo(1_000 - succeeded);
    }

    @RepeatedTest(10)
    void sameUserCannotRedeemCouponTwiceConcurrently() throws Exception {
        Product product = createProduct(1_000, new BigDecimal("100000"));
        Coupon coupon = createCoupon(100);
        User buyer = createUser(Role.USER);

        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> orderService.createOrder(buyer.getId(), checkout(coupon.getCode(), item(product, 1))));
        }
        int succeeded = runConcurrently(tasks);

        long usages = couponUsageRepository.findAll().stream()
                .filter(cu -> cu.getCoupon().getId().equals(coupon.getId())).count();
        System.out.printf("[CONCURRENCY] coupon-per-user: attempts=10 succeeded=%d usages=%d%n", succeeded, usages);

        assertThat(usages).isEqualTo(1);
        assertThat(succeeded).isEqualTo(1);
    }

    @RepeatedTest(10)
    void cancellationsAndNewOrdersDoNotLoseStockUpdates() throws Exception {
        Product product = createProduct(50, new BigDecimal("10000"));
        List<User> cancellers = createUsers(10);
        List<Long> orderIds = new ArrayList<>();
        for (User u : cancellers) {
            OrderResponse order = orderService.createOrder(u.getId(), checkout(null, item(product, 1)));
            orderIds.add(order.getId());
        }
        List<User> buyers = createUsers(20);

        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < cancellers.size(); i++) {
            Long orderId = orderIds.get(i);
            Long userId = cancellers.get(i).getId();
            tasks.add(() -> { orderService.cancelOrder(orderId, userId); return null; });
        }
        buyers.forEach(u -> tasks.add(() -> orderService.createOrder(u.getId(), checkout(null, item(product, 1)))));
        int succeeded = runConcurrently(tasks);

        long finalStock = stockOf(product);
        System.out.printf("[CONCURRENCY] cancel+order: start=40 cancels=10 newOrders=20 succeeded=%d finalStock=%d expected=30%n",
                succeeded, finalStock);

        assertThat(succeeded).isEqualTo(30);
        assertThat(finalStock).isEqualTo(30);
    }

    @RepeatedTest(10)
    void multiItemOrdersInOppositeOrderDoNotDeadlock() throws Exception {
        Product a = createProduct(1_000, new BigDecimal("10000"));
        Product b = createProduct(1_000, new BigDecimal("10000"));
        List<User> buyers = createUsers(THREADS);

        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < buyers.size(); i++) {
            Long userId = buyers.get(i).getId();
            CheckoutRequest request = i % 2 == 0
                    ? checkout(null, item(a, 1), item(b, 1))
                    : checkout(null, item(b, 1), item(a, 1));
            tasks.add(() -> orderService.createOrder(userId, request));
        }
        int succeeded = runConcurrently(tasks);

        System.out.printf("[CONCURRENCY] deadlock: threads=%d succeeded=%d stockA=%d stockB=%d%n",
                THREADS, succeeded, stockOf(a), stockOf(b));

        assertThat(succeeded).isEqualTo(THREADS);
        assertThat(stockOf(a)).isEqualTo(1_000 - THREADS);
        assertThat(stockOf(b)).isEqualTo(1_000 - THREADS);
    }

    // --- helpers ---

    /** Runs all tasks released at the same instant; returns how many completed without exception. */
    private int runConcurrently(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (Callable<?> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    task.call();
                    ok.incrementAndGet();
                } catch (Exception expectedForLosers) {
                    // business rejection (out of stock, coupon exhausted) is the expected outcome for losers
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

    private List<User> createUsers(int n) {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            users.add(createUser(Role.USER));
        }
        return users;
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

    private CartItemRequest item(Product product, int qty) {
        CartItemRequest item = new CartItemRequest();
        item.setProductId(product.getId());
        item.setQuantity(qty);
        return item;
    }

    private CheckoutRequest checkout(String couponCode, CartItemRequest... items) {
        CheckoutRequest request = new CheckoutRequest();
        request.setFullName("Buyer");
        request.setPhoneNumber("0912345678");
        request.setShippingAddress("HN");
        // STRIPE skips the synchronous confirmation e-mail path, keeping the test focused on stock/coupon.
        request.setPaymentMethod(PaymentMethod.STRIPE);
        request.setItems(List.of(items));
        request.setCode(couponCode);
        return request;
    }

    private long stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStockQuantity();
    }

    private long countSold(Product product) {
        return orderDetailRepository.findAll().stream()
                .filter(od -> od.getProduct().getId().equals(product.getId()))
                .mapToLong(od -> od.getQuantity())
                .sum();
    }
}
