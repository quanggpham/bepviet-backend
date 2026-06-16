package com.example.demo.service;

import com.example.demo.dto.response.SepayCheckoutResponse;
import com.example.demo.dto.response.StripeCheckoutResponse;
import com.example.demo.entity.Order;
import com.example.demo.entity.Payment;
import com.example.demo.enums.OrderStatus;
import com.example.demo.enums.PaymentMethod;
import com.example.demo.enums.PaymentStatus;
import com.example.demo.exception.BusinessException;
import com.example.demo.exception.AccessDeniedException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.PaymentRepository;
import com.example.demo.repository.ProcessedWebhookEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final Pattern SEPAY_ORDER_CODE_PATTERN = Pattern.compile("(?i)DH(\\d{1,10})");
    /** Stripe requires a Checkout Session to live at least 30 minutes. */
    private static final Duration STRIPE_SESSION_LIFETIME = Duration.ofMinutes(31);
    private static final Duration WEBHOOK_GRACE = Duration.ofMinutes(4);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ProcessedWebhookEventRepository processedWebhookEventRepository;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    @Value("${sepay.merchant-id}")
    private String merchantId;

    @Value("${sepay.secret-key}")
    private String secretKey;

    @Value("${sepay.webhook-api-key:}")
    private String sepayWebhookApiKey;

    @Value("${sepay.bank-name:}")
    private String sepayBankName;

    @Value("${sepay.bank-bin:}")
    private String sepayBankBin;

    @Value("${sepay.bank-account:}")
    private String sepayBankAccount;

    @Value("${sepay.account-name:}")
    private String sepayAccountName;

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    @Value("${stripe.publishable-key:}")
    private String stripePublishableKey;

    @Value("${stripe.webhook-secret:}")
    private String stripeWebhookSecret;

    @Value("${stripe.success-url}")
    private String stripeSuccessUrl;

    @Value("${stripe.cancel-url}")
    private String stripeCancelUrl;

    @Value("${app.order-expiry.ttl-minutes:60}")
    private long orderTtlMinutes;

    public SepayCheckoutResponse preparePayment(Long orderId, Long userId) throws Exception {
        Order order = findOwnedOrder(orderId, userId);
        if (order.getPaymentMethod() != PaymentMethod.SEPAY && order.getPaymentMethod() != PaymentMethod.BANK_TRANSFER) {
            throw new BusinessException("Đơn hàng này không sử dụng phương thức thanh toán chuyển khoản");
        }

        String finalAmount = order.getTotalMoney()
                .setScale(0, RoundingMode.HALF_UP)
                .toPlainString();
        String paymentCode = buildSePayOrderCode(order.getId());

        Payment payment = paymentRepository.findByOrderIdAndPaymentMethod(orderId, PaymentMethod.SEPAY)
                .orElseGet(() -> Payment.builder()
                        .order(order)
                        .paymentMethod(PaymentMethod.SEPAY)
                        .amount(order.getTotalMoney())
                        .status(PaymentStatus.PENDING)
                        .build());
        payment.setAmount(order.getTotalMoney());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setNote(paymentCode);
        paymentRepository.save(payment);

        return SepayCheckoutResponse.builder()
                .bankName(sepayBankName)
                .bankAccount(sepayBankAccount)
                .accountName(sepayAccountName)
                .amount(order.getTotalMoney().setScale(0, RoundingMode.HALF_UP))
                .content(paymentCode)
                .qrUrl(buildVietQrUrl(paymentCode, finalAmount))
                .orderId(order.getId())
                .build();
    }

    @Transactional
    public void handleSePayWebhook(String payload, String authorizationHeader) {
        verifySePayAuthorization(authorizationHeader);

        JsonNode root = parseWebhookPayload(payload);
        String transferType = readText(root, "transferType");
        if (!"in".equalsIgnoreCase(transferType)) {
            log.info("Bo qua SePay webhook transferType={}", transferType);
            return;
        }

        // SePay retries until it gets a 2xx. Recording the transfer id in this transaction makes a
        // redelivery a no-op, while a failed attempt rolls back and can be retried.
        String transactionReference = readSePayTransactionReference(root);
        if (transactionReference != null
                && processedWebhookEventRepository.insertIfAbsent(transactionReference, "SEPAY", "transfer.in") == 0) {
            log.info("Bo qua SePay webhook trung giao dich: {}", transactionReference);
            return;
        }

        String paymentCodeSource = firstNonBlank(
                readText(root, "code"),
                readText(root, "content"),
                readText(root, "description")
        );
        Long orderId = extractOrderIdFromSePayCode(paymentCodeSource);
        BigDecimal transferAmount = readAmount(root, "transferAmount");

        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng"));
        if (order.getPaymentMethod() != PaymentMethod.SEPAY && order.getPaymentMethod() != PaymentMethod.BANK_TRANSFER) {
            throw new BusinessException("Đơn hàng không sử dụng phương thức thanh toán SePay");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("SePay nhan tien cho orderId={} o trang thai {} (reference={}): can doi soat/hoan tien thu cong",
                    orderId, order.getStatus(), transactionReference);
            return;
        }
        if (transferAmount.compareTo(order.getTotalMoney().setScale(0, RoundingMode.HALF_UP)) != 0) {
            throw new BusinessException("Số tiền chuyển khoản không khớp đơn hàng");
        }

        Payment payment = paymentRepository.findByOrderIdAndPaymentMethod(orderId, PaymentMethod.SEPAY)
                .orElseGet(() -> Payment.builder()
                        .order(order)
                        .paymentMethod(PaymentMethod.SEPAY)
                        .amount(order.getTotalMoney())
                        .status(PaymentStatus.PENDING)
                        .build());

        payment.setTransactionReference(transactionReference);
        payment.setAmount(transferAmount);
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setGatewayResponseJson(payload);
        payment.setNote(buildSePayOrderCode(order.getId()));
        paymentRepository.save(payment);

        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        Order emailReadyOrder = orderRepository.findByIdWithEmailDetails(order.getId())
                .orElse(order);
        emailService.sendOrderConfirmationEmail(emailReadyOrder);

        log.info("Da xu ly SePay webhook thanh cong: orderId={}, amount={}, reference={}",
                orderId, transferAmount, transactionReference);
    }

    @Transactional
    public StripeCheckoutResponse prepareStripeCheckout(Long orderId, Long userId) throws StripeException {
        ensureStripeConfigured();

        Order order = findOwnedOrder(orderId, userId);

        if (order.getPaymentMethod() != PaymentMethod.STRIPE) {
            throw new BusinessException("Đơn hàng này không sử dụng phương thức thanh toán Stripe");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException("Đơn hàng không còn ở trạng thái chờ thanh toán");
        }
        // The session must expire before the unpaid-order job may cancel the order,
        // otherwise a customer could pay for an order whose stock was already released.
        LocalDateTime orderExpiresAt = order.getCreatedAt().plusMinutes(orderTtlMinutes);
        if (LocalDateTime.now().plus(STRIPE_SESSION_LIFETIME).plus(WEBHOOK_GRACE).isAfter(orderExpiresAt)) {
            throw new BusinessException("Đơn hàng sắp hết hạn thanh toán, vui lòng đặt lại đơn mới");
        }

        Payment existingPayment = paymentRepository.findByOrderIdAndPaymentMethod(orderId, PaymentMethod.STRIPE)
                .orElse(null);

        if (existingPayment != null && existingPayment.getStatus() == PaymentStatus.COMPLETED) {
            throw new BusinessException("Đơn hàng này đã được thanh toán thành công");
        }

        Stripe.apiKey = stripeSecretKey;

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(stripeSuccessUrl)
                .setCancelUrl(stripeCancelUrl)
                .setClientReferenceId(String.valueOf(order.getId()))
                .setExpiresAt(Instant.now().plus(STRIPE_SESSION_LIFETIME).getEpochSecond())
                .putMetadata("orderId", String.valueOf(order.getId()))
                .putMetadata("paymentMethod", PaymentMethod.STRIPE.name())
                .setPaymentIntentData(
                        SessionCreateParams.PaymentIntentData.builder()
                                .putMetadata("orderId", String.valueOf(order.getId()))
                                .putMetadata("paymentMethod", PaymentMethod.STRIPE.name())
                                .build()
                )
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setQuantity(1L)
                                .setPriceData(
                                        SessionCreateParams.LineItem.PriceData.builder()
                                                .setCurrency("vnd")
                                                .setUnitAmount(toStripeAmount(order.getTotalMoney()))
                                                .setProductData(
                                                        SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                .setName("Thanh toán đơn hàng #" + order.getId())
                                                                .setDescription("Don hang #" + order.getId())
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                )
                .build();

        Session session = Session.create(params);

        Payment payment = existingPayment != null ? existingPayment : Payment.builder()
                .order(order)
                .paymentMethod(PaymentMethod.STRIPE)
                .amount(order.getTotalMoney())
                .status(PaymentStatus.PENDING)
                .build();

        payment.setTransactionReference(session.getId());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setGatewayResponseJson(session.toJson());
        paymentRepository.save(payment);

        return StripeCheckoutResponse.builder()
                .sessionId(session.getId())
                .checkoutUrl(session.getUrl())
                .publishableKey(stripePublishableKey)
                .build();
    }

    @Transactional
    public void handleStripeWebhook(String payload, String stripeSignature) {
        ensureStripeWebhookConfigured();

        Event event;
        try {
            event = Webhook.constructEvent(payload, stripeSignature, stripeWebhookSecret);
        } catch (SignatureVerificationException ex) {
            throw new BusinessException("Stripe webhook signature không hợp lệ");
        } catch (Exception ex) {
            throw new BusinessException("Payload webhook Stripe không hợp lệ");
        }

        JsonNode root = parseWebhookPayload(payload);
        String eventType = readText(root, "type");

        log.info("Nhan Stripe webhook event: id={}, type={}", event.getId(), eventType);

        // Stripe delivers at-least-once. Recording the event id in the same transaction means a
        // redelivery is acknowledged without side effects, and a failed attempt can be retried.
        if (processedWebhookEventRepository.insertIfAbsent(event.getId(), "STRIPE", eventType) == 0) {
            log.info("Bo qua Stripe event da xu ly: {}", event.getId());
            return;
        }

        switch (eventType) {
            case "checkout.session.completed" -> handleCheckoutSessionCompleted(root, payload);
            case "payment_intent.succeeded" -> handlePaymentIntentSucceeded(root, payload);
            case "payment_intent.payment_failed" -> handlePaymentIntentFailed(root, payload);
            default -> log.info("Bo qua Stripe event type: {}", eventType);
        }
    }

    private void handleCheckoutSessionCompleted(JsonNode root, String payload) {
        JsonNode sessionNode = root.path("data").path("object");
        String sessionId = readText(sessionNode, "id");
        String orderIdValue = readText(sessionNode.path("metadata"), "orderId");

        log.info("Xu ly checkout.session.completed: sessionId={}, orderId={}", sessionId, orderIdValue);

        if (sessionId == null || orderIdValue == null) {
            log.warn("Thieu sessionId/orderId trong checkout.session.completed");
            return;
        }
        if (!"paid".equals(readText(sessionNode, "payment_status"))) {
            log.info("Checkout session {} chua thanh toan (payment_status={}), bo qua",
                    sessionId, readText(sessionNode, "payment_status"));
            return;
        }

        confirmStripePayment(Long.valueOf(orderIdValue), sessionId, sessionNode.path("amount_total").asLong(-1), payload);
    }

    private void handlePaymentIntentSucceeded(JsonNode root, String payload) {
        JsonNode paymentIntentNode = root.path("data").path("object");
        String orderIdValue = readText(paymentIntentNode.path("metadata"), "orderId");

        log.info("Xu ly payment_intent.succeeded: orderId={}", orderIdValue);

        if (orderIdValue == null) {
            log.warn("Thieu orderId trong payment_intent.succeeded");
            return;
        }

        Long orderId = Long.valueOf(orderIdValue);
        Payment payment = paymentRepository.findByOrderIdAndPaymentMethod(orderId, PaymentMethod.STRIPE)
                .orElse(null);
        if (payment == null) {
            log.warn("Khong tim thay payment STRIPE cho orderId={} khi xu ly payment_intent.succeeded", orderId);
            return;
        }
        confirmStripePayment(orderId, payment.getTransactionReference(),
                paymentIntentNode.path("amount_received").asLong(-1), payload);
    }

    private void handlePaymentIntentFailed(JsonNode root, String payload) {
        JsonNode paymentIntentNode = root.path("data").path("object");
        String orderIdValue = readText(paymentIntentNode.path("metadata"), "orderId");

        log.info("Xu ly payment_intent.payment_failed: orderId={}", orderIdValue);

        if (orderIdValue == null) {
            log.warn("Thieu orderId trong payment_intent.payment_failed");
            return;
        }

        Long orderId = Long.valueOf(orderIdValue);
        Payment payment = paymentRepository.findByOrderIdAndPaymentMethod(orderId, PaymentMethod.STRIPE)
                .orElse(null);
        if (payment == null) {
            log.warn("Khong tim thay payment STRIPE cho orderId={} khi xu ly payment_intent.payment_failed", orderId);
            return;
        }

        payment.setStatus(PaymentStatus.FAILED);
        payment.setGatewayResponseJson(payload);
        paymentRepository.save(payment);
    }

    /**
     * Moves a PENDING Stripe order to CONFIRMED exactly once. The order row is locked so that
     * different events for the same order (checkout.session.completed, payment_intent.succeeded)
     * are applied one after another and only the first one makes the transition.
     */
    private void confirmStripePayment(Long orderId, String sessionId, long paidAmount, String payload) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            log.warn("Stripe bao thanh toan cho orderId={} khong ton tai", orderId);
            return;
        }
        if (order.getPaymentMethod() != PaymentMethod.STRIPE) {
            log.warn("Bo qua Stripe payment: orderId={} dung phuong thuc {}", orderId, order.getPaymentMethod());
            return;
        }
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            log.info("Order {} da CONFIRMED, bo qua event trung", orderId);
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("Stripe da thu tien cho orderId={} o trang thai {}: can hoan tien thu cong", orderId, order.getStatus());
            return;
        }
        long expectedAmount = toStripeAmount(order.getTotalMoney());
        if (paidAmount != expectedAmount) {
            log.warn("So tien Stripe khong khop orderId={}: expected={}, paid={}", orderId, expectedAmount, paidAmount);
            return;
        }

        Payment payment = (sessionId == null ? Optional.<Payment>empty() : paymentRepository.findByTransactionReference(sessionId))
                .orElseGet(() -> Payment.builder()
                        .order(order)
                        .paymentMethod(PaymentMethod.STRIPE)
                        .amount(order.getTotalMoney())
                        .transactionReference(sessionId)
                        .build());
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setGatewayResponseJson(payload);
        paymentRepository.save(payment);

        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        Order emailReadyOrder = orderRepository.findByIdWithEmailDetails(order.getId())
                .orElse(order);
        emailService.sendOrderConfirmationEmail(emailReadyOrder);
    }

    private Long toStripeAmount(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private Order findOwnedOrder(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng"));
        if (!order.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Bạn không có quyền thao tác với đơn hàng này");
        }
        return order;
    }

    private void ensureStripeConfigured() {
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            throw new BusinessException("Stripe secret key chưa được cấu hình");
        }
    }

    private void ensureStripeWebhookConfigured() {
        if (stripeWebhookSecret == null || stripeWebhookSecret.isBlank()) {
            throw new BusinessException("Stripe webhook secret chưa được cấu hình");
        }
    }

    private JsonNode parseWebhookPayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new BusinessException("Không thể parse payload webhook Stripe");
        }
    }

    private void verifySePayAuthorization(String authorizationHeader) {
        if (sepayWebhookApiKey == null || sepayWebhookApiKey.isBlank()) {
            throw new BusinessException("SePay webhook API key chưa được cấu hình");
        }
        String expectedHeader = "Apikey " + sepayWebhookApiKey;
        if (authorizationHeader == null || !expectedHeader.equals(authorizationHeader)) {
            throw new BusinessException("SePay webhook API key không hợp lệ");
        }
    }

    private Long extractOrderIdFromSePayCode(String value) {
        if (value == null) {
            throw new BusinessException("Webhook SePay thiếu mã thanh toán DH");
        }
        Matcher matcher = SEPAY_ORDER_CODE_PATTERN.matcher(value);
        if (!matcher.find()) {
            throw new BusinessException("Không tìm thấy mã thanh toán DH trong webhook SePay");
        }
        return Long.valueOf(matcher.group(1));
    }

    private BigDecimal readAmount(JsonNode root, String fieldName) {
        JsonNode valueNode = root.path(fieldName);
        if (valueNode.isMissingNode() || valueNode.isNull()) {
            throw new BusinessException("Webhook SePay thiếu số tiền chuyển khoản");
        }
        return new BigDecimal(valueNode.asText()).setScale(0, RoundingMode.HALF_UP);
    }

    private String readSePayTransactionReference(JsonNode root) {
        String id = readText(root, "id");
        if (id != null) {
            return "sepay:" + id;
        }
        String referenceCode = readText(root, "referenceCode");
        return referenceCode != null ? "sepay:" + referenceCode : null;
    }

    private String buildSePayOrderCode(Long orderId) {
        return "DH" + orderId;
    }

    private String buildVietQrUrl(String paymentCode, String amount) {
        if (sepayBankBin == null || sepayBankBin.isBlank()
                || sepayBankAccount == null || sepayBankAccount.isBlank()) {
            return "";
        }

        String encodedContent = URLEncoder.encode(paymentCode, StandardCharsets.UTF_8);
        String encodedAccountName = URLEncoder.encode(
                sepayAccountName == null ? "" : sepayAccountName,
                StandardCharsets.UTF_8
        );
        return "https://img.vietqr.io/image/"
                + sepayBankBin.trim()
                + "-"
                + sepayBankAccount.trim()
                + "-compact2.png?amount="
                + amount
                + "&addInfo="
                + encodedContent
                + "&accountName="
                + encodedAccountName;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String readText(JsonNode node, String fieldName) {
        JsonNode valueNode = node.path(fieldName);
        if (valueNode.isMissingNode() || valueNode.isNull() || valueNode.asText().isBlank()) {
            return null;
        }
        return valueNode.asText();
    }
}
