package com.example.demo.service;

import com.example.demo.dto.request.CartItemRequest;
import com.example.demo.dto.request.CheckoutRequest;
import com.example.demo.dto.response.OrderResponse;
import com.example.demo.dto.response.PageResponse;
import com.example.demo.entity.CouponUsage;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderDetail;
import com.example.demo.entity.Product;
import com.example.demo.entity.User;
import com.example.demo.enums.OrderStatus;
import com.example.demo.enums.PaymentMethod;
import com.example.demo.exception.AccessDeniedException;
import com.example.demo.exception.BusinessException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.mapper.OrderMapper;
import com.example.demo.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderMapper orderMapper;
    private final CartItemRepository cartItemRepository;
    private final CouponService couponService;
    private final EmailService emailService;
    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;

    @Transactional
    public OrderResponse createOrder(Long userId, CheckoutRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Order order = Order.builder()
                .user(user)
                .fullName(request.getFullName())
                .note(request.getNote())
                .paymentMethod(request.getPaymentMethod())
                .status(OrderStatus.PENDING)
                .subTotal(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .shippingAddress(request.getShippingAddress())
                .totalMoney(BigDecimal.ZERO)
                .phoneNumber(request.getPhoneNumber())
                .build();

        BigDecimal subTotal = BigDecimal.ZERO;
        List<CartItemRequest> pricingItems = new ArrayList<>();
        Map<Long, Integer> quantityByProductId = new TreeMap<>();
        Map<Long, Product> productsById = new HashMap<>();

        for (CartItemRequest item : request.getItems())
        {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", "", item.getProductId()));
            if (Boolean.FALSE.equals(product.getIsActive())) {
                throw new BusinessException("Sản phẩm " + product.getName() + " hiện không còn bán");
            }

            quantityByProductId.merge(product.getId(), item.getQuantity(), Integer::sum);
            productsById.put(product.getId(), product);
            subTotal = subTotal.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            pricingItems.add(item);


            OrderDetail orderDetail = OrderDetail.builder()
                    .product(product)
                    .quantity(item.getQuantity())
                    .price(product.getPrice())
                    .build();

            order.addOrderDetail(orderDetail);
        }

        // TreeMap iterates by ascending product id, so concurrent multi-item orders lock rows in the same order.
        quantityByProductId.forEach((productId, quantity) -> {
            if (productRepository.decreaseStock(productId, quantity) == 0) {
                throw new BusinessException("Sản phẩm " + productsById.get(productId).getName() + " không đủ số lượng tồn kho");
            }
        });

        order.setSubTotal(subTotal);

        String code = request.getCode();
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (code != null && !code.trim().isEmpty()) {
            discountAmount = couponService.evaluateCoupon(request.getCode(), pricingItems, userId).discountAmount();
        }

        BigDecimal totalMoney = subTotal.subtract(discountAmount);
        order.setTotalMoney(totalMoney);
        order.setDiscountAmount(discountAmount);

        Order savedOrder = orderRepository.save(order);

        if (code != null && !code.trim().isEmpty() && discountAmount.compareTo(BigDecimal.ZERO) > 0) {
            couponService.markCouponAsUsed(code, user, savedOrder, discountAmount);
        }
        
        List<Long> productIds = request.getItems().stream()
                .map(CartItemRequest::getProductId)
                .toList();

        cartItemRepository.deleteByUserIdAndProductIdIn(user.getId(), productIds);

        if (request.getPaymentMethod() != PaymentMethod.STRIPE) {
            Order emailReadyOrder = orderRepository.findByIdWithEmailDetails(savedOrder.getId())
                    .orElse(savedOrder);
            emailService.sendOrderConfirmationEmail(emailReadyOrder);
        }

        return orderMapper.toOrderResponse(savedOrder);
    }

    public PageResponse<OrderResponse> getAll(Pageable pageable, OrderStatus status) {
        int size = pageable.getPageSize();
        int validPageSize= Math.min(size, 50);

        Pageable newPageable = PageRequest.of(pageable.getPageNumber(), validPageSize, pageable.getSort());

        Page<Order> pageData = status == null
                ? orderRepository.findAll(newPageable)
                : orderRepository.findAllByStatus(status, newPageable);
        List<OrderResponse> response= pageData.getContent().stream()
                .map(orderMapper::toOrderResponse)
                .toList();

        return PageResponse.<OrderResponse>builder()
                .items(response)
                .pageSize(pageData.getSize())
                .totalPages(pageData.getTotalPages())
                .totalElements(pageData.getTotalElements())
                .currentPage(pageData.getNumber() + 1)
                .build();
    }

    public OrderResponse getById(Long orderId, Long userId) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại"));

        if (!order.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Bạn không có quyền truy cập");
        }

        return orderMapper.toOrderResponse(order);
    }

    @Transactional
    public void cancelOrder(Long orderId, Long userId) {
        // Row lock: concurrent cancels, the expiry job and payment webhooks see each other's status change.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại"));

        if (!order.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Bạn không có quyền truy cập");
        }

        if (!order.getStatus().equals(OrderStatus.PENDING)) {
            throw new BusinessException("Chỉ đơn hàng đang chờ xác nhận mới có thể hủy");
        }
        cancelAndRelease(order);
    }

    /**
     * Cancels an unpaid Stripe order created before {@code createdBefore}. Returns false when the order
     * was paid, cancelled or otherwise changed in the meantime; the row lock makes this safe against webhooks.
     */
    @Transactional
    public boolean expireUnpaidOrder(Long orderId, LocalDateTime createdBefore) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null
                || order.getStatus() != OrderStatus.PENDING
                || order.getPaymentMethod() != PaymentMethod.STRIPE
                || !order.getCreatedAt().isBefore(createdBefore)) {
            return false;
        }
        cancelAndRelease(order);
        return true;
    }

    /** Caller must hold the order row lock and have checked that the order is PENDING. */
    private void cancelAndRelease(Order order) {
        order.setStatus(OrderStatus.CANCELLED);

        Map<Long, Integer> quantityByProductId = new TreeMap<>();
        for (OrderDetail item : order.getOrderDetails()) {
            quantityByProductId.merge(item.getProduct().getId(), item.getQuantity(), Integer::sum);
        }
        quantityByProductId.forEach(productRepository::increaseStock);

        for (CouponUsage usage : couponUsageRepository.findByOrderId(order.getId())) {
            couponRepository.releaseUsage(usage.getCoupon().getId());
            couponUsageRepository.delete(usage);
        }

        orderRepository.save(order);
    }

    public PageResponse<OrderResponse> getAllByUserId(Long userId, Pageable pageable, OrderStatus status) {
        int size = pageable.getPageSize();
        int validPageSize= Math.min(size, 50);

        Pageable finalPageable = PageRequest.of(pageable.getPageNumber(), validPageSize, pageable.getSort());
        Page<Order> pageData = status == null
                ? orderRepository.findAllByUserId(userId, finalPageable)
                : orderRepository.findAllByUserIdAndStatus(userId, status, finalPageable);

        List<OrderResponse> responses =  pageData.getContent().stream()
                .map(orderMapper::toOrderResponse)
                .toList();

        return PageResponse.<OrderResponse>builder()
                .currentPage(pageData.getNumber() + 1)
                .items(responses)
                .pageSize(pageData.getSize())
                .totalElements(pageData.getTotalElements())
                .totalPages(pageData.getTotalPages())
                .build();
    }

    public OrderResponse adminGetById(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng"));

        return orderMapper.toOrderResponse(order);
    }

    @Transactional
    public void updateStatus(Long orderId, OrderStatus orderStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hagnf"));

        order.setStatus(orderStatus);
        orderRepository.save(order);
    }
}
