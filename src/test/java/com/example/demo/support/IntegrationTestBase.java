package com.example.demo.support;

import com.example.demo.entity.Category;
import com.example.demo.entity.Product;
import com.example.demo.entity.User;
import com.example.demo.enums.Role;
import com.example.demo.repository.CategoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.JwtService;
import com.example.demo.security.UserPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Boots the full application against a throwaway PostgreSQL container.
 * External services (Gemini, Pinecone, Stripe, Cloudinary, SMTP) get dummy values;
 * tests must not depend on them being reachable.
 */
@SpringBootTest(properties = {
        "application.security.jwt.secret-key=dGVzdC1vbmx5LWp3dC1zZWNyZXQtMzItYnl0ZXMtbG9uZyEh",
        "cloudinary.cloud-name=test", "cloudinary.api-key=test", "cloudinary.api-secret=test",
        "spring.ai.google.genai.api-key=test", "spring.ai.google.genai.embedding.api-key=test",
        "spring.ai.google.genai.chat.api-key=test", "spring.ai.vectorstore.pinecone.api-key=test",
        "stripe.secret-key=sk_test_dummy", "stripe.publishable-key=pk_test_dummy",
        "stripe.webhook-secret=" + IntegrationTestBase.STRIPE_WEBHOOK_SECRET,
        "spring.mail.host=localhost", "spring.mail.port=1",
        "app.cart-reminder.enabled=false",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO",
        "logging.level.org.hibernate.type.descriptor.sql.BasicBinder=INFO"
})
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    public static final String STRIPE_WEBHOOK_SECRET = "whsec_test_secret";

    /** users.phone is unique and all test classes share one database, so random numbers eventually collide. */
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected CategoryRepository categoryRepository;

    @Autowired
    protected ProductRepository productRepository;

    protected Product createProduct(long stock, BigDecimal price) {
        Category category = categoryRepository.save(Category.builder()
                .name("Cat " + UUID.randomUUID().toString().substring(0, 8))
                .build());
        return productRepository.save(Product.builder()
                .name("Product " + UUID.randomUUID().toString().substring(0, 8))
                .price(price)
                .stockQuantity(stock)
                .category(category)
                .isActive(true)
                .build());
    }

    protected User createUser(Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = User.builder()
                .fullName("Test " + role)
                .email(role.name().toLowerCase() + "-" + suffix + "@test.vn")
                .phone(uniquePhone())
                .password("{noop}unused")
                .role(role)
                .build();
        return userRepository.save(user);
    }

    protected static String uniquePhone() {
        return String.format("09%08d", PHONE_SEQUENCE.incrementAndGet());
    }

    protected String tokenFor(User user) {
        return jwtService.generateToken(UserPrincipal.create(user));
    }

    protected String bearer(User user) {
        return "Bearer " + tokenFor(user);
    }
}
