package com.example.demo.catalog;

import com.example.demo.entity.Product;
import com.example.demo.entity.ProductLike;
import com.example.demo.entity.User;
import com.example.demo.enums.Role;
import com.example.demo.repository.ProductLikeRepository;
import com.example.demo.support.IntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The number of SQL statements behind a product list must not grow with the number of products on the page.
 * Every product gets its own category, likes and review stats so that any per-row lazy load shows up.
 */
class ProductQueryCountTest extends IntegrationTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    ProductLikeRepository productLikeRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    Statistics statistics;

    @BeforeEach
    void enableStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @Test
    void productSearchUsesConstantNumberOfQueries() throws Exception {
        User viewer = createUser(Role.USER);
        String small = seedProducts(2, viewer);
        String large = seedProducts(10, viewer);

        long smallQueries = statementsFor(viewer, "/api/v1/products?size=50&name=" + small, 2);
        long largeQueries = statementsFor(viewer, "/api/v1/products?size=50&name=" + large, 10);
        System.out.printf("[N+1] product search: 2 products -> %d statements, 10 products -> %d statements%n",
                smallQueries, largeQueries);

        assertThat(largeQueries).isEqualTo(smallQueries);
    }

    @Test
    void likedProductsUsesConstantNumberOfQueries() throws Exception {
        User fewLikes = createUser(Role.USER);
        User manyLikes = createUser(Role.USER);
        seedProducts(2, fewLikes);
        seedProducts(10, manyLikes);

        long smallQueries = statementsFor(fewLikes, "/api/v1/products/liked", 2);
        long largeQueries = statementsFor(manyLikes, "/api/v1/products/liked", 10);
        System.out.printf("[N+1] liked products: 2 likes -> %d statements, 10 likes -> %d statements%n",
                smallQueries, largeQueries);

        assertThat(largeQueries).isEqualTo(smallQueries);
    }

    @Test
    void searchStillReturnsLikeAndRatingData() throws Exception {
        User viewer = createUser(Role.USER);
        String prefix = seedProducts(1, viewer);

        mockMvc.perform(get("/api/v1/products?name=" + prefix).header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].likeCount").value(1))
                .andExpect(jsonPath("$.data.items[0].liked").value(true))
                .andExpect(jsonPath("$.data.items[0].avgRating").value(4.5))
                .andExpect(jsonPath("$.data.items[0].totalReviews").value(2))
                .andExpect(jsonPath("$.data.items[0].category.name").isNotEmpty());

        mockMvc.perform(get("/api/v1/products?name=" + prefix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].likeCount").value(1))
                .andExpect(jsonPath("$.data.items[0].liked").value(false));
    }

    /** Creates {@code count} products in distinct categories, each liked by {@code liker} and with review stats. */
    private String seedProducts(int count, User liker) {
        String prefix = "n1" + UUID.randomUUID().toString().substring(0, 8);
        List<Product> products = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Product product = createProduct(100, new BigDecimal("10000"));
            product.setName(prefix + "-" + i);
            products.add(productRepository.save(product));
        }
        for (Product product : products) {
            productLikeRepository.save(ProductLike.builder().user(liker).product(product).build());
            jdbcTemplate.update("INSERT INTO product_stats (product_id, avg_rating, total_reviews, rating_distribution, updated_at) "
                    + "VALUES (?, 4.50, 2, '{}', now())", product.getId());
        }
        return prefix;
    }

    private long statementsFor(User user, String url, int expectedItems) throws Exception {
        String authorization = bearer(user);
        statistics.clear();
        MvcResult result = mockMvc.perform(get(url).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andReturn();
        long statements = statistics.getPrepareStatementCount();
        String body = result.getResponse().getContentAsString();
        assertThat(body.split("\"likeCount\"", -1).length - 1).isEqualTo(expectedItems);
        return statements;
    }
}
