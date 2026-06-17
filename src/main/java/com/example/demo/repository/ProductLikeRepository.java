package com.example.demo.repository;

import com.example.demo.entity.Product;
import com.example.demo.entity.ProductLike;
import com.example.demo.entity.User;
import com.example.demo.repository.projection.ProductLikeCountProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public interface ProductLikeRepository extends JpaRepository<ProductLike, Long> {
    Optional<ProductLike> findByUserAndProduct(User user, Product product);
    boolean existsByUserAndProduct(User user, Product product);
    boolean existsByUserIdAndProductId(Long userId, Long productId);
    long countByProduct(Product product);
    long countByProductId(Long productId);
    List<ProductLike> findByUserOrderByCreatedAtDesc(User user);

    @Query("select l from ProductLike l join fetch l.product p join fetch p.category "
            + "where l.user.id = :userId order by l.createdAt desc")
    List<ProductLike> findByUserIdWithProduct(@Param("userId") Long userId);

    @Query("select l.product.id as productId, count(l) as total from ProductLike l "
            + "where l.product.id in :productIds group by l.product.id")
    List<ProductLikeCountProjection> countGroupedByProductId(@Param("productIds") Collection<Long> productIds);

    @Query("select l.product.id from ProductLike l where l.user.id = :userId and l.product.id in :productIds")
    Set<Long> findLikedProductIds(@Param("userId") Long userId, @Param("productIds") Collection<Long> productIds);

    /** Like counts for a page of products in one query; products without likes are absent from the map. */
    default Map<Long, Long> countByProductIds(Collection<Long> productIds) {
        return countGroupedByProductId(productIds).stream()
                .collect(Collectors.toMap(ProductLikeCountProjection::getProductId, ProductLikeCountProjection::getTotal));
    }
}
