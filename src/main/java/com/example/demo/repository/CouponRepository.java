package com.example.demo.repository;

import com.example.demo.entity.Coupon;
import com.example.demo.enums.PromotionType;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long>, JpaSpecificationExecutor<Coupon> {
    Optional<Coupon> findByCode(String code);

    boolean existsByCode(@NotBlank(message = "Mã giảm giá không được để trống") String code);

    Page<Coupon> findAllByPromotionType(PromotionType promotionType, Pageable pageable);

    /** Atomically consumes one use; returns 0 when the usage limit is already reached. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Coupon c SET c.usedCount = c.usedCount + 1 WHERE c.id = :couponId AND c.usedCount < c.usageLimit")
    int incrementUsage(@Param("couponId") Long couponId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Coupon c SET c.usedCount = c.usedCount - 1 WHERE c.id = :couponId AND c.usedCount > 0")
    int releaseUsage(@Param("couponId") Long couponId);
}
