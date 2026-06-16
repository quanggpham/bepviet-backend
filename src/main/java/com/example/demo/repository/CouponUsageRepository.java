package com.example.demo.repository;

import com.example.demo.entity.CouponUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CouponUsageRepository extends JpaRepository<CouponUsage, Long> {
    boolean existsByCouponIdAndUserId(Long couponId, Long userId);

    List<CouponUsage> findByOrderId(Long orderId);
}
