package com.example.demo.repository.specification;

import com.example.demo.entity.Product;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;

public class ProductSpecification {
    public static Specification<Product> hasName(String name) {
        return (root, query, cb) -> (name == null || name.trim().isEmpty()) 
                ? null 
                : cb.like(cb.lower(root.get("name").as(String.class)), "%" + name.trim().toLowerCase() + "%");
    }

    /** Products hidden by an admin; rows created before the flag existed have NULL and stay listed. */
    public static Specification<Product> isListed() {
        return (root, query, cb) -> cb.or(cb.isNull(root.get("isActive")), cb.isTrue(root.get("isActive")));
    }

    public static Specification<Product> hasCategory(Long categoryId) {
        return (root, query, cb) -> (categoryId == null) ? null : cb.equal(root.get("category").get("id"), categoryId);
    }

    public static Specification<Product> hasPrice(BigDecimal minPrice, BigDecimal maxPrice) {
        return (root, query, cb) -> {
            if (minPrice == null && maxPrice == null) {
                return null;
            }
            if (minPrice != null && maxPrice != null) {
                return cb.between(root.get("price").as(BigDecimal.class), minPrice, maxPrice);
            }
            if (minPrice != null) {
                return cb.greaterThanOrEqualTo(root.get("price").as(BigDecimal.class), minPrice);
            }
            return cb.lessThanOrEqualTo(root.get("price").as(BigDecimal.class), maxPrice);
        };
    }
}
