package com.example.demo.payment;

import com.example.demo.entity.Order;
import com.example.demo.entity.Payment;
import com.example.demo.entity.User;
import com.example.demo.enums.OrderStatus;
import com.example.demo.enums.PaymentMethod;
import com.example.demo.enums.PaymentStatus;
import com.example.demo.enums.Role;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.PaymentRepository;
import com.example.demo.service.EmailService;
import com.example.demo.support.IntegrationTestBase;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Sends Stripe webhook payloads signed with the test secret, exactly as Stripe would.
 */
class StripeWebhookTest extends IntegrationTestBase {

    private static final String URL = "/api/v1/payments/stripe/webhook";
    private static final BigDecimal TOTAL = new BigDecimal("150000");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @MockitoBean
    EmailService emailService;

    @RepeatedTest(10)
    void sameEventDeliveredTenTimesConcurrentlyIsProcessedOnce() throws Exception {
        clearInvocations(emailService);
        Order order = createOrder(PaymentMethod.STRIPE, OrderStatus.PENDING);
        String sessionId = "cs_" + UUID.randomUUID();
        String payload = checkoutCompleted("evt_" + UUID.randomUUID(), order.getId(), sessionId, "paid", 150000);

        List<Integer> codes = sendConcurrently(payload, 10);
        System.out.printf("[WEBHOOK] duplicate-concurrent: deliveries=10 codes=%s paymentRows=%d status=%s%n",
                codes, paymentsFor(sessionId), statusOf(order));

        assertThat(codes).containsOnly(200);
        assertThat(paymentsFor(sessionId)).isEqualTo(1);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.CONFIRMED);
        verify(emailService, times(1)).sendOrderConfirmationEmail(any());
    }

    @Test
    void sameEventDeliveredSequentiallyIsAcknowledgedAndProcessedOnce() throws Exception {
        clearInvocations(emailService);
        Order order = createOrder(PaymentMethod.STRIPE, OrderStatus.PENDING);
        String payload = checkoutCompleted("evt_" + UUID.randomUUID(), order.getId(), "cs_" + UUID.randomUUID(), "paid", 150000);

        for (int i = 0; i < 5; i++) {
            assertThat(send(payload, sign(payload))).isEqualTo(200);
        }
        verify(emailService, times(1)).sendOrderConfirmationEmail(any());
    }

    @RepeatedTest(10)
    void checkoutAndPaymentIntentEventsForSameOrderConfirmOnce() throws Exception {
        clearInvocations(emailService);
        Order order = createOrder(PaymentMethod.STRIPE, OrderStatus.PENDING);
        String sessionId = "cs_" + UUID.randomUUID();
        paymentRepository.save(Payment.builder().order(order).paymentMethod(PaymentMethod.STRIPE)
                .amount(TOTAL).status(PaymentStatus.PENDING).transactionReference(sessionId).build());

        List<String> payloads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            payloads.add(checkoutCompleted("evt_" + UUID.randomUUID(), order.getId(), sessionId, "paid", 150000));
            payloads.add(paymentIntentSucceeded("evt_" + UUID.randomUUID(), order.getId(), 150000));
        }
        List<Integer> codes = sendAllConcurrently(payloads);
        System.out.printf("[WEBHOOK] mixed-events: deliveries=10 codes=%s status=%s%n", codes, statusOf(order));

        assertThat(codes).containsOnly(200);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.CONFIRMED);
        verify(emailService, times(1)).sendOrderConfirmationEmail(any());
    }

    @Test
    void paidEventDoesNotResurrectCancelledOrder() throws Exception {
        Order order = createOrder(PaymentMethod.STRIPE, OrderStatus.CANCELLED);
        String payload = checkoutCompleted("evt_" + UUID.randomUUID(), order.getId(), "cs_" + UUID.randomUUID(), "paid", 150000);

        assertThat(send(payload, sign(payload))).isEqualTo(200);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void paidEventIsIgnoredForNonStripeOrder() throws Exception {
        Order order = createOrder(PaymentMethod.COD, OrderStatus.PENDING);
        String payload = checkoutCompleted("evt_" + UUID.randomUUID(), order.getId(), "cs_" + UUID.randomUUID(), "paid", 150000);

        assertThat(send(payload, sign(payload))).isEqualTo(200);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void amountMismatchDoesNotConfirmOrder() throws Exception {
        Order order = createOrder(PaymentMethod.STRIPE, OrderStatus.PENDING);
        String payload = checkoutCompleted("evt_" + UUID.randomUUID(), order.getId(), "cs_" + UUID.randomUUID(), "paid", 1);

        assertThat(send(payload, sign(payload))).isEqualTo(200);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void unpaidSessionDoesNotConfirmOrder() throws Exception {
        Order order = createOrder(PaymentMethod.STRIPE, OrderStatus.PENDING);
        String payload = checkoutCompleted("evt_" + UUID.randomUUID(), order.getId(), "cs_" + UUID.randomUUID(), "unpaid", 150000);

        assertThat(send(payload, sign(payload))).isEqualTo(200);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PENDING);
        verify(emailService, never()).sendOrderConfirmationEmail(any());
    }

    @Test
    void invalidSignatureIsRejected() throws Exception {
        Order order = createOrder(PaymentMethod.STRIPE, OrderStatus.PENDING);
        String payload = checkoutCompleted("evt_" + UUID.randomUUID(), order.getId(), "cs_" + UUID.randomUUID(), "paid", 150000);

        assertThat(send(payload, "t=" + System.currentTimeMillis() / 1000 + ",v1=deadbeef")).isEqualTo(400);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void validPaidEventConfirmsPendingStripeOrder() throws Exception {
        Order order = createOrder(PaymentMethod.STRIPE, OrderStatus.PENDING);
        String sessionId = "cs_" + UUID.randomUUID();
        String payload = checkoutCompleted("evt_" + UUID.randomUUID(), order.getId(), sessionId, "paid", 150000);

        assertThat(send(payload, sign(payload))).isEqualTo(200);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(paymentRepository.findByTransactionReference(sessionId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
    }

    // --- helpers ---

    private Order createOrder(PaymentMethod method, OrderStatus status) {
        User user = createUser(Role.USER);
        return orderRepository.save(Order.builder()
                .user(user)
                .fullName("Buyer")
                .phoneNumber("0912345678")
                .shippingAddress("HN")
                .paymentMethod(method)
                .status(status)
                .subTotal(TOTAL)
                .totalMoney(TOTAL)
                .build());
    }

    private String checkoutCompleted(String eventId, Long orderId, String sessionId, String paymentStatus, long amountTotal) {
        return """
                {"id":"%s","object":"event","type":"checkout.session.completed","data":{"object":{
                 "id":"%s","object":"checkout.session","payment_status":"%s","amount_total":%d,"currency":"vnd",
                 "metadata":{"orderId":"%d"}}}}
                """.formatted(eventId, sessionId, paymentStatus, amountTotal, orderId);
    }

    private String paymentIntentSucceeded(String eventId, Long orderId, long amount) {
        return """
                {"id":"%s","object":"event","type":"payment_intent.succeeded","data":{"object":{
                 "id":"pi_%s","object":"payment_intent","status":"succeeded","amount_received":%d,"currency":"vnd",
                 "metadata":{"orderId":"%d"}}}}
                """.formatted(eventId, UUID.randomUUID(), amount, orderId);
    }

    private String sign(String payload) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(STRIPE_WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + signature;
    }

    private int send(String payload, String signatureHeader) throws Exception {
        return mockMvc.perform(post(URL)
                        .header("Stripe-Signature", signatureHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn().getResponse().getStatus();
    }

    private List<Integer> sendConcurrently(String payload, int times) throws Exception {
        List<String> payloads = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            payloads.add(payload);
        }
        return sendAllConcurrently(payloads);
    }

    private List<Integer> sendAllConcurrently(List<String> payloads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(payloads.size());
        CountDownLatch ready = new CountDownLatch(payloads.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String payload : payloads) {
            String signature = sign(payload);
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return send(payload, signature);
            }));
        }
        ready.await();
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : futures) {
            codes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return codes.stream().sorted().toList();
    }

    private long paymentsFor(String sessionId) {
        return paymentRepository.findAll().stream()
                .filter(p -> sessionId.equals(p.getTransactionReference()))
                .count();
    }

    private OrderStatus statusOf(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }
}
