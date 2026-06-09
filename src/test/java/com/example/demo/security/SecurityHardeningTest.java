package com.example.demo.security;

import com.example.demo.entity.Product;
import com.example.demo.entity.User;
import com.example.demo.enums.Role;
import com.example.demo.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityHardeningTest extends IntegrationTestBase {

    @Autowired
    MockMvc mockMvc;

    private String productJson(Product product, String price) {
        return """
                {"name":"Hacked","price":%s,"stockQuantity":1,"categoryId":%d}
                """.formatted(price, product.getCategory().getId());
    }

    @Test
    void regularUserCannotCreateUpdateOrDeleteProducts() throws Exception {
        Product product = createProduct(10, new BigDecimal("50000"));
        String user = bearer(createUser(Role.USER));

        mockMvc.perform(post("/api/v1/products").header("Authorization", user)
                        .contentType(MediaType.APPLICATION_JSON).content(productJson(product, "1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/products/" + product.getId()).header("Authorization", user)
                        .contentType(MediaType.APPLICATION_JSON).content(productJson(product, "1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/products/" + product.getId()).header("Authorization", user))
                .andExpect(status().isForbidden());

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getPrice()).isEqualByComparingTo("50000");
    }

    @Test
    void adminCanStillCreateProducts() throws Exception {
        Product product = createProduct(10, new BigDecimal("50000"));
        mockMvc.perform(post("/api/v1/products").header("Authorization", bearer(createUser(Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(productJson(product, "1000")))
                .andExpect(status().isCreated());
    }

    @Test
    void regularUserCanStillLikeAndUnlikeProducts() throws Exception {
        Product product = createProduct(10, new BigDecimal("50000"));
        String user = bearer(createUser(Role.USER));
        mockMvc.perform(post("/api/v1/products/" + product.getId() + "/like").header("Authorization", user))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(delete("/api/v1/products/" + product.getId() + "/like").header("Authorization", user))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void productCreationValidatesBody() throws Exception {
        mockMvc.perform(post("/api/v1/products").header("Authorization", bearer(createUser(Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkoutRejectsNegativeQuantityAndLeavesStockUntouched() throws Exception {
        Product cheap = createProduct(10, new BigDecimal("10000"));
        Product expensive = createProduct(10, new BigDecimal("90000"));
        String body = """
                {"fullName":"A","phoneNumber":"0912345678","shippingAddress":"HN","paymentMethod":"COD",
                 "items":[{"productId":%d,"quantity":1},{"productId":%d,"quantity":-1}]}
                """.formatted(cheap.getId(), expensive.getId());

        mockMvc.perform(post("/api/v1/orders").header("Authorization", bearer(createUser(Role.USER)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertThat(productRepository.findById(expensive.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
        assertThat(productRepository.findById(cheap.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
    }

    @Test
    void checkoutRejectsEmptyBodyWith400() throws Exception {
        mockMvc.perform(post("/api/v1/orders").header("Authorization", bearer(createUser(Role.USER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addToCartRejectsNonPositiveQuantity() throws Exception {
        Product product = createProduct(10, new BigDecimal("10000"));
        mockMvc.perform(post("/api/v1/carts/add").header("Authorization", bearer(createUser(Role.USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":%d,\"quantity\":-5}".formatted(product.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"A","email":"login-401@test.vn","phone":"0987654321","password":"Passw0rd!"}
                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login-401@test.vn\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@test.vn\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedJwtReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/carts").header("Authorization", "Bearer abc.def.ghi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingTokenOnProtectedEndpointReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/carts"))
                .andExpect(status().isUnauthorized());
    }
}
