package com.example.demo.catalog;

import com.example.demo.config.DatabaseSeeder;
import com.example.demo.entity.Category;
import com.example.demo.entity.Product;
import com.example.demo.entity.User;
import com.example.demo.enums.Role;
import com.example.demo.support.IntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public catalog reads are answered from the cache without touching the database,
 * and every admin write that changes what they return makes the next read fresh.
 */
class CatalogCacheTest extends IntegrationTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    ObjectProvider<CacheManager> cacheManager;

    @Autowired
    DatabaseSeeder databaseSeeder;

    Statistics statistics;
    String admin;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        // other test classes write through repositories, which bypass eviction
        cacheManager.ifAvailable(manager -> manager.getCacheNames().forEach(name -> manager.getCache(name).clear()));
        admin = bearer(createUser(Role.ADMIN));
    }

    @Test
    void categoriesComeFromCacheUntilAdminChangesThem() throws Exception {
        Product product = createProduct(10, new BigDecimal("10000"));
        Long categoryId = product.getCategory().getId();
        String renamed = "Ren " + UUID.randomUUID().toString().substring(0, 8);

        get200("/api/v1/categories");
        assertThat(statementsFor("/api/v1/categories")).isZero();

        mockMvc.perform(put("/api/v1/categories/" + categoryId).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + renamed + "\"}"))
                .andExpect(status().isOk());
        assertThat(get200("/api/v1/categories")).contains(renamed);

        String created = "New " + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/categories").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + created + "\"}"))
                .andExpect(status().isCreated());
        assertThat(get200("/api/v1/categories")).contains(created);
    }

    /** The web server accepts requests before startup seeding finishes; what it cached meanwhile must not stick. */
    @Test
    void startupSeedingDoesNotLeaveAStaleCatalogInTheCache() throws Exception {
        get200("/api/v1/categories");
        String seeded = "Seed " + UUID.randomUUID().toString().substring(0, 8);
        categoryRepository.save(Category.builder().name(seeded).build()); // the seeder writes through repositories

        databaseSeeder.run();

        assertThat(get200("/api/v1/categories")).contains(seeded);
    }

    @Test
    void activeBannersComeFromCacheUntilAdminChangesThem() throws Exception {
        get200("/api/v1/banners");
        assertThat(statementsFor("/api/v1/banners")).isZero();

        String title = "Banner " + UUID.randomUUID().toString().substring(0, 8);
        String body = mockMvc.perform(post("/api/v1/admin/banners").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"imageUrl\":\"https://img.test/b.png\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(get200("/api/v1/banners")).contains(title);

        String id = body.replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1");
        mockMvc.perform(delete("/api/v1/admin/banners/" + id).header("Authorization", admin))
                .andExpect(status().isNoContent());
        assertThat(get200("/api/v1/banners")).doesNotContain(title);
    }

    @Test
    void publicProductListComesFromCacheUntilAdminChangesProducts() throws Exception {
        Product product = namedProduct();
        String url = "/api/v1/products?name=" + product.getName();

        assertThat(get200(url)).contains(product.getName());
        assertThat(statementsFor(url)).isZero();

        String renamed = product.getName() + " v2";
        mockMvc.perform(put("/api/v1/products/" + product.getId()).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + renamed + "\",\"categoryId\":" + product.getCategory().getId() + "}"))
                .andExpect(status().isOk());
        assertThat(get200(url)).contains(renamed);

        mockMvc.perform(delete("/api/v1/products/" + product.getId()).header("Authorization", admin))
                .andExpect(status().isOk());
        assertThat(get200(url)).doesNotContain(renamed);
    }

    @Test
    void renamingCategoryRefreshesProductsThatEmbedIt() throws Exception {
        Product product = namedProduct();
        String url = "/api/v1/products?name=" + product.getName();
        get200(url);

        String renamed = "Ren " + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(put("/api/v1/categories/" + product.getCategory().getId()).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + renamed + "\"}"))
                .andExpect(status().isOk());
        assertThat(get200(url)).contains(renamed);
    }

    @Test
    void signedInProductListIsNotSharedBetweenUsers() throws Exception {
        Product product = namedProduct();
        User liker = createUser(Role.USER);
        String url = "/api/v1/products?name=" + product.getName();

        get200(url);
        mockMvc.perform(post("/api/v1/products/" + product.getId() + "/like").header("Authorization", bearer(liker)))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get(url).header("Authorization", bearer(liker)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"liked\":true");
    }

    private Product namedProduct() {
        Product product = createProduct(10, new BigDecimal("10000"));
        product.setName("cache" + UUID.randomUUID().toString().substring(0, 8));
        return productRepository.save(product);
    }

    private String get200(String url) throws Exception {
        return mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long statementsFor(String url) throws Exception {
        statistics.clear();
        get200(url);
        long statements = statistics.getPrepareStatementCount();
        System.out.printf("[CACHE] %s -> %d statements on repeat request%n", url, statements);
        return statements;
    }
}
