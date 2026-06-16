package com.example.demo.repository;

import com.example.demo.dto.response.OverviewStatisticResponse;
import com.example.demo.dto.response.RevenueByDateProjection;
import com.example.demo.dto.response.TopProductProjection;
import com.example.demo.entity.Order;
import com.example.demo.enums.OrderStatus;
import org.springframework.cglib.core.Local;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order> findAllByUserId(Long userId, Pageable pageable);
    Page<Order> findAllByUserIdAndStatus(Long userId, OrderStatus status, Pageable pageable);
    Page<Order> findAllByStatus(OrderStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "orderDetails", "orderDetails.product"})
    @Query("SELECT o FROM Order o WHERE o.id = :orderId")
    java.util.Optional<Order> findByIdWithEmailDetails(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :orderId")
    java.util.Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);

    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.paymentMethod = :paymentMethod AND o.createdAt < :createdBefore")
    List<Long> findIdsByStatusAndPaymentMethodCreatedBefore(@Param("status") OrderStatus status,
                                                            @Param("paymentMethod") com.example.demo.enums.PaymentMethod paymentMethod,
                                                            @Param("createdBefore") LocalDateTime createdBefore);

    boolean existsByUserIdAndCreatedAtAfter(Long userId, LocalDateTime createdAt);

    @Query("SELECT new com.example.demo.dto.response.OverviewStatisticResponse(" +
            "COUNT(o.id), " +
            "COALESCE(SUM(o.totalMoney), 0)) " +
            "FROM Order o " +
            "WHERE o.status = :status " +
            "AND o.createdAt >= :startDate AND o.createdAt <= :endDate")
    OverviewStatisticResponse getOverviewStatistic(
            @Param("status") OrderStatus status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query(value = "SELECT DATE(created_at) as date, SUM(total_money) as revenue " +
            "FROM orders " +
            "WHERE status = :status " +
            "AND created_at >= :startDate AND created_at <= :endDate " +
            "GROUP BY DATE(created_at) " +
            "ORDER BY date ASC",
            nativeQuery = true)
    List<RevenueByDateProjection> getRevenueByDate (
            @Param("status") String status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate")  LocalDateTime endDate
            );

    @Query(value = "SELECT p.id as productId, p.name as productName, p.image_url as productImage, SUM(od.quantity) as totalQuantity " +
            "FROM order_details od " +
            "JOIN products p ON od.product_id = p.id " +
            "JOIN orders o ON od.order_id = o.id " +
            "WHERE o.status = :status " +
            "AND o.created_at >= :startDate AND o.created_at <= :endDate " +
            "GROUP BY p.id, p.name, p.image_url ",
            nativeQuery = true)
    List<TopProductProjection> getTopSellingProducts(
            @Param("status") String status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable
    );
}
