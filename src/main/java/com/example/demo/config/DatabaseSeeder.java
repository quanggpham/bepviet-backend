package com.example.demo.config;

import com.example.demo.entity.*;
import com.example.demo.enums.DiscountType;
import com.example.demo.enums.OrderStatus;
import com.example.demo.enums.PaymentMethod;
import com.example.demo.enums.Role;
import com.example.demo.repository.*;
import com.example.demo.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final PasswordEncoder passwordEncoder;
    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final CartRepository cartRepository;
    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;

    // Requests are served while this runs, so anything they cached may predate the seeded rows.
    @Override
    @CacheEvict(cacheNames = {CacheConfig.CATEGORIES, CacheConfig.PUBLIC_PRODUCTS, CacheConfig.ACTIVE_BANNERS}, allEntries = true)
    public void run(String... args) throws Exception {

        Faker faker = new Faker(new Locale("vi"));
        Random random = new Random();

        boolean seededAny = false;

        // 1. TẠO TÀI KHOẢN ADMIN VÀ KHÁCH HÀNG MẪU (NẾU CHƯA CÓ USER)
        if (userRepository.count() == 0) {
            log.info("Database chưa có người dùng. Bắt đầu seed User...");
            User admin = User.builder()
                    .fullName("Quản Trị Viên")
                    .email("admin@gmail.com")
                    .password(passwordEncoder.encode("123456"))
                    .role(Role.ADMIN)
                    .build();
            userRepository.save(admin);

            for (int i = 0; i < 10; i++) {
                User user = User.builder()
                        .fullName(faker.name().fullName())
                        .email(faker.internet().emailAddress())
                        .password(passwordEncoder.encode("123456"))
                        .role(Role.USER)
                        .build();
                userRepository.save(user);
            }
            seededAny = true;
        }

        // 2. TẠO DANH MỤC VÀ SẢN PHẨM MẪU (NẾU CHƯA CÓ DANH MỤC)
        if (categoryRepository.count() == 0) {
            log.info("Database chưa có danh mục sản phẩm. Bắt đầu seed Category & Product...");
            List<String> categoryNames = List.of("Đồ ăn nhanh", "Đồ uống", "Tráng miệng");

            for (String categoryName : categoryNames) {
                Category category = Category.builder().name(categoryName).build();
                categoryRepository.save(category);

                for (int j = 0; j < 5; j++) {
                    Product product = Product.builder()
                            .category(category)
                            .name(faker.food().dish())
                            .description(faker.lorem().sentence(10))
                            .price(BigDecimal.valueOf(faker.number().numberBetween(20000, 150000)))
                            .stockQuantity((long) faker.number().numberBetween(10, 100))
                            .isActive(true) // Set default active state to true
                            .build();
                    productRepository.save(product);
                }
            }
            seededAny = true;
        }

        // 3. TẠO MÃ GIẢM GIÁ (NẾU CHƯA CÓ COUPON)
        if (couponRepository.count() == 0) {
            log.info("Database chưa có mã giảm giá. Bắt đầu seed Coupon...");
            List<Coupon> coupons = new ArrayList<>();
            coupons.add(Coupon.builder()
                    .code("GIAM50K")
                    .discountType(DiscountType.FIXED_AMOUNT)
                    .discountValue(new BigDecimal("50000"))
                    .minOrderValue(new BigDecimal("150000"))
                    .usageLimit(100)
                    .usedCount(0)
                    .startDate(LocalDate.now().minusDays(5))
                    .expirationDate(LocalDate.now().plusDays(30))
                    .active(true)
                    .build());

            coupons.add(Coupon.builder()
                    .code("GIAM20PT")
                    .discountType(DiscountType.PERCENTAGE)
                    .discountValue(new BigDecimal("20"))
                    .maxDiscountAmount(new BigDecimal("30000"))
                    .minOrderValue(new BigDecimal("100000"))
                    .usageLimit(50)
                    .usedCount(0)
                    .startDate(LocalDate.now().minusDays(10))
                    .expirationDate(LocalDate.now().plusDays(15))
                    .active(true)
                    .build());
            couponRepository.saveAll(coupons);
            seededAny = true;
        }

        // 4. TẠO GIỎ HÀNG VÀ ĐƠN HÀNG GIẢ (NẾU CHƯA CÓ ĐƠN HÀNG)
        if (orderRepository.count() == 0) {
            List<User> allUsers = userRepository.findAll();
            List<Product> allProducts = productRepository.findAll();

            if (!allUsers.isEmpty() && !allProducts.isEmpty()) {
                log.info("Bắt đầu seed Giỏ hàng và Đơn hàng mẫu...");
                for (User user : allUsers) {
                    if (user.getRole() == Role.ADMIN) continue;

                    cartRepository.findByUser(user)
                            .orElseGet(() -> {
                                Cart newCart = new Cart();
                                newCart.setUser(user);
                                user.setCart(newCart);
                                return cartRepository.save(newCart);
                            });

                    int cartItemCount = faker.number().numberBetween(1, 4);
                    for (int i = 0; i < cartItemCount; i++) {
                        Product randomProduct = allProducts.get(random.nextInt(allProducts.size()));
                        CartItem cartItem = CartItem.builder()
                                .cart(user.getCart())
                                .product(randomProduct)
                                .quantity(faker.number().numberBetween(1, 3))
                                .build();
                        cartItemRepository.save(cartItem);
                    }

                    int orderCount = faker.number().numberBetween(1, 6);
                    for (int i = 0; i < orderCount; i++) {
                        OrderStatus randomStatus = OrderStatus.values()[random.nextInt(OrderStatus.values().length)];
                        Order order = Order.builder()
                                .user(user)
                                .fullName(user.getFullName())
                                .phoneNumber(faker.phoneNumber().cellPhone())
                                .shippingAddress(faker.address().fullAddress())
                                .note(faker.lorem().sentence(5))
                                .status(randomStatus)
                                .paymentMethod(PaymentMethod.COD)
                                .totalMoney(BigDecimal.ZERO)
                                .build();
                        BigDecimal totalMoney = BigDecimal.ZERO;

                        int orderDetailCount = faker.number().numberBetween(1, 5);
                        for (int j = 0; j < orderDetailCount; j++) {
                            Product randomProduct = allProducts.get(random.nextInt(allProducts.size()));
                            int qty = faker.number().numberBetween(1, 4);

                            OrderDetail detail = OrderDetail.builder()
                                    .product(randomProduct)
                                    .price(randomProduct.getPrice())
                                    .quantity(qty)
                                    .build();

                            order.addOrderDetail(detail);
                            totalMoney = totalMoney.add(randomProduct.getPrice().multiply(BigDecimal.valueOf(qty)));
                        }

                        order.setTotalMoney(totalMoney);
                        order.setSubTotal(totalMoney);
                        order.setDiscountAmount(BigDecimal.ZERO);
                        order.setCreatedAt(LocalDateTime.now().minusDays(random.nextInt(30)));

                        OrderStatus status = (random.nextInt(10) < 7) ? OrderStatus.COMPLETED : OrderStatus.CANCELLED;
                        order.setStatus(status);
                        orderRepository.save(order);
                    }
                }
                seededAny = true;
            }
        }

        if (seededAny) {
            log.info("Khởi tạo dữ liệu mẫu thành công hoàn tất!");
        }
    }
}