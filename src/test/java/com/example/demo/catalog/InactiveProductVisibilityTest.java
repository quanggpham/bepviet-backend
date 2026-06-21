package com.example.demo.catalog;

import com.example.demo.entity.Product;
import com.example.demo.enums.Role;
import com.example.demo.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hidden products stay out of the storefront (list, cache, detail, checkout) but remain manageable by admins. */
class InactiveProductVisibilityTest extends IntegrationTestBase {

    @Autowired
    MockMvc mockMvc;

    @Test
    void hiddenProductIsNotListedForAnonymousOrSignedInShoppers() throws Exception {
        Product visible = namedProduct(true);
        Product hidden = namedProduct(false);
        String shopper = bearer(createUser(Role.USER));

        assertThat(list(visible.getName(), null)).contains(visible.getName());
        assertThat(list(hidden.getName(), null)).doesNotContain(hidden.getName());
        assertThat(list(hidden.getName(), shopper)).doesNotContain(hidden.getName());
    }

    @Test
    void adminStillSeesHiddenProductsSoTheyCanBeShownAgain() throws Exception {
        Product hidden = namedProduct(false);

        assertThat(list(hidden.getName(), bearer(createUser(Role.ADMIN)))).contains(hidden.getName());
    }

    @Test
    void hidingAndShowingAProductRefreshesTheCachedList() throws Exception {
        Product product = namedProduct(true);
        String admin = bearer(createUser(Role.ADMIN));

        assertThat(list(product.getName(), null)).contains(product.getName());

        setActive(product, false, admin);
        assertThat(list(product.getName(), null)).doesNotContain(product.getName());

        setActive(product, true, admin);
        assertThat(list(product.getName(), null)).contains(product.getName());
    }

    @Test
    void hiddenProductDetailIsNotFoundForShoppersButVisibleToAdmins() throws Exception {
        Product hidden = namedProduct(false);

        mockMvc.perform(get("/api/v1/products/" + hidden.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/products/" + hidden.getId()).header("Authorization", bearer(createUser(Role.USER))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/products/" + hidden.getId()).header("Authorization", bearer(createUser(Role.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void hiddenProductCannotBeCheckedOutAndKeepsItsStock() throws Exception {
        Product hidden = namedProduct(false);

        mockMvc.perform(post("/api/v1/orders").header("Authorization", bearer(createUser(Role.USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Buyer","phoneNumber":"0912345678","shippingAddress":"HN",
                                 "paymentMethod":"COD","items":[{"productId":%d,"quantity":1}]}
                                """.formatted(hidden.getId())))
                .andExpect(status().isBadRequest());
        assertThat(productRepository.findById(hidden.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
    }

    private void setActive(Product product, boolean active, String admin) throws Exception {
        mockMvc.perform(put("/api/v1/products/" + product.getId()).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":" + active + ",\"categoryId\":" + product.getCategory().getId() + "}"))
                .andExpect(status().isOk());
    }

    private Product namedProduct(boolean active) {
        Product product = createProduct(10, new BigDecimal("10000"));
        product.setName("vis" + UUID.randomUUID().toString().substring(0, 8));
        product.setIsActive(active);
        return productRepository.save(product);
    }

    private String list(String name, String authorization) throws Exception {
        var request = get("/api/v1/products?name=" + name);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
