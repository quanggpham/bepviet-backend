package com.example.demo.payment;

import com.example.demo.entity.Order;
import com.example.demo.enums.OrderStatus;
import com.example.demo.enums.PaymentMethod;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class SepayWebhookTest extends IntegrationTestBase {

    private static final String URL = "/api/v1/payments/sepay/webhook";
    private static final BigDecimal TOTAL = new BigDecimal("150000");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @MockitoBean
    EmailService emailService;

    @RepeatedTest(30)
    void sameTransferDeliveredTenTimesConcurrentlyIsProcessedOnce() throws Exception {
        clearInvocations(emailService);
        Order order = createOrder(OrderStatus.PENDING);
        long transferId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        String payload = transfer(transferId, order.getId(), 150000);

        List<Integer> codes = sendConcurrently(payload, 10);
        long rows = paymentRepository.findAll().stream()
                .filter(p -> ("sepay:" + transferId).equals(p.getTransactionReference())).count();
        System.out.printf("[SEPAY] duplicate-concurrent: deliveries=10 codes=%s paymentRows=%d status=%s%n",
                codes, rows, statusOf(order));

        assertThat(codes).containsOnly(200);
        assertThat(rows).isEqualTo(1);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.CONFIRMED);
        verify(emailService, times(1)).sendOrderConfirmationEmail(any());
    }

    @Test
    void transferForCancelledOrderDoesNotConfirmIt() throws Exception {
        clearInvocations(emailService);
        Order order = createOrder(OrderStatus.CANCELLED);

        assertThat(send(transfer(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), order.getId(), 150000), auth()))
                .isEqualTo(200);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.CANCELLED);
        verify(emailService, never()).sendOrderConfirmationEmail(any());
    }

    @Test
    void secondTransferForConfirmedOrderSendsNoSecondEmail() throws Exception {
        clearInvocations(emailService);
        Order order = createOrder(OrderStatus.PENDING);

        assertThat(send(transfer(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), order.getId(), 150000), auth())).isEqualTo(200);
        assertThat(send(transfer(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), order.getId(), 150000), auth())).isEqualTo(200);

        assertThat(statusOf(order)).isEqualTo(OrderStatus.CONFIRMED);
        verify(emailService, times(1)).sendOrderConfirmationEmail(any());
    }

    @Test
    void wrongApiKeyIsRejected() throws Exception {
        Order order = createOrder(OrderStatus.PENDING);
        assertThat(send(transfer(1L, order.getId(), 150000), "Apikey wrong")).isEqualTo(400);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PENDING);
    }

    // --- helpers ---

    private Order createOrder(OrderStatus status) {
        return orderRepository.save(Order.builder()
                .user(createUser(Role.USER))
                .fullName("Buyer")
                .phoneNumber("0912345678")
                .shippingAddress("HN")
                .paymentMethod(PaymentMethod.SEPAY)
                .status(status)
                .subTotal(TOTAL)
                .totalMoney(TOTAL)
                .build());
    }

    private String transfer(long transferId, Long orderId, long amount) {
        return """
                {"id":%d,"gateway":"MBBank","transferType":"in","transferAmount":%d,
                 "content":"DH%d thanh toan","code":"DH%d","referenceCode":"FT%d"}
                """.formatted(transferId, amount, orderId, orderId, transferId);
    }

    private String auth() {
        return "Apikey " + SEPAY_WEBHOOK_API_KEY;
    }

    private int send(String payload, String authorization) throws Exception {
        return mockMvc.perform(post(URL).header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn().getResponse().getStatus();
    }

    private List<Integer> sendConcurrently(String payload, int times) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(times);
        CountDownLatch ready = new CountDownLatch(times);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return send(payload, auth());
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

    private OrderStatus statusOf(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }
}
