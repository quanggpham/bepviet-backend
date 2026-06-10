package com.example.demo.service;

import com.example.demo.dto.request.CartItemRequest;
import com.example.demo.dto.request.CouponCalculateRequest;
import com.example.demo.dto.request.CouponRequest;
import com.example.demo.dto.response.CouponCalculationResponse;
import com.example.demo.dto.response.CouponResponse;
import com.example.demo.dto.response.PageResponse;
import com.example.demo.entity.Category;
import com.example.demo.entity.Coupon;
import com.example.demo.entity.CouponUsage;
import com.example.demo.entity.Order;
import com.example.demo.entity.Product;
import com.example.demo.entity.User;
import com.example.demo.enums.DiscountType;
import com.example.demo.enums.PromotionType;
import com.example.demo.exception.BaseException;
import com.example.demo.exception.BusinessException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.mapper.CouponMapper;
import com.example.demo.repository.CategoryRepository;
import com.example.demo.repository.CouponRepository;
import com.example.demo.repository.CouponUsageRepository;
import com.example.demo.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CouponService {
    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CouponMapper  couponMapper;

    @Transactional(readOnly = true)
    public CouponCalculationResponse calculateDiscount(CouponCalculateRequest request, Long userId) {
        CouponCalculationResult result = evaluateCoupon(request.getCode(), request.getItems(), userId);
        return toCalculationResponse(result);
    }

    @Transactional
    public void markCouponAsUsed(String code, User user, Order order, BigDecimal appliedDiscount) {
        Coupon coupon = couponRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mã giảm giá"));

        if (couponRepository.incrementUsage(coupon.getId()) == 0) {
            throw new BusinessException("Mã giảm giá đã hết");
        }

        CouponUsage couponUsage = CouponUsage.builder()
                .coupon(coupon)
                .user(user)
                .order(order)
                .discountAmount(appliedDiscount)
                .build();
        try {
            couponUsageRepository.saveAndFlush(couponUsage);
        } catch (DataIntegrityViolationException ex) {
            // unique (coupon_id, user_id): a concurrent order by the same user already redeemed it
            throw new BusinessException("Bạn đã sử dụng mã giảm giá này rồi");
        }
    }

    @Transactional
    public CouponResponse createCoupon(CouponRequest request) {
        if (couponRepository.existsByCode(request.getCode())) {
            throw new BaseException("Mã giảm giá đã tồn tại: " + request.getCode());
        }

        validateCouponLogic(request);

        Coupon coupon = couponMapper.toCoupon(request);
        coupon.setCode(request.getCode().toUpperCase());
        coupon.setUsedCount(0);
        assignPromotionTarget(coupon, request);
        return couponMapper.toCouponResponse(couponRepository.save(coupon));
    }

    @Transactional(readOnly = true)
    public PageResponse<CouponResponse> getAllCoupons(PromotionType promotionType, Long categoryId, Long productId, Pageable pageable) {
        int validPageSize = Math.min(pageable.getPageSize(), 50);
        Pageable newPageable = PageRequest.of(pageable.getPageNumber(), validPageSize, pageable.getSort());

        Page<Coupon> pageData = couponRepository.findAll(buildCouponSpecification(promotionType, categoryId, productId), newPageable);

        List<CouponResponse> response = pageData.getContent().stream()
                .map(couponMapper::toCouponResponse)
                .toList();

        return PageResponse.<CouponResponse>builder()
                .items(response)
                .pageSize(pageData.getSize())
                .totalPages(pageData.getTotalPages())
                .totalElements(pageData.getTotalElements())
                .currentPage(pageData.getNumber() + 1)
                .build();
    }

    @Transactional
    public CouponResponse updateCoupon(Long id, CouponRequest request) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mã giảm giá"));

        if (!coupon.getCode().equalsIgnoreCase(request.getCode()) && couponRepository.existsByCode(request.getCode())) {
            throw new BaseException("Mã giảm giá mới đã tồn tại trong hệ thống");
        }

        validateCouponLogic(request);

        couponMapper.updateCouponFromRequest(request, coupon);
        coupon.setCode(request.getCode().toUpperCase());
        assignPromotionTarget(coupon, request);

        return couponMapper.toCouponResponse(couponRepository.save(coupon));
    }

    @Transactional(readOnly = true)
    public CouponResponse getCouponById(Long id) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mã giảm giá với ID: " + id));
        return couponMapper.toCouponResponse(coupon);
    }

    @Transactional
    public void deleteCoupon(Long id) {
        if (!couponRepository.existsById(id)) {
            throw new ResourceNotFoundException("Không tìm thấy mã giảm giá để xóa");
        }
        couponRepository.deleteById(id);
    }

    private void validateCouponLogic(CouponRequest request) {
        if (request.getStartDate().isAfter(request.getExpirationDate())) {
            throw new BaseException("Ngày bắt đầu không được lớn hơn ngày kết thúc");
        }

        if (request.getDiscountType() == DiscountType.PERCENTAGE) {
            if (request.getDiscountValue().compareTo(new java.math.BigDecimal("100")) > 0) {
                throw new BaseException("Giá trị giảm theo phần trăm không được vượt quá 100");
            }
            if (request.getMaxDiscountAmount() == null) {
                throw new BaseException("Vui lòng thiết lập số tiền giảm tối đa (maxDiscountAmount) cho mã giảm theo %");
            }
        }

        if (request.getPromotionType() == PromotionType.ORDER) {
            if (request.getCategoryId() != null || request.getProductId() != null) {
                throw new BaseException("Khuyến mãi ORDER không được gắn categoryId hoặc productId");
            }
        }

        if (request.getPromotionType() == PromotionType.CATEGORY) {
            if (request.getCategoryId() == null) {
                throw new BaseException("Khuyến mãi CATEGORY bắt buộc có categoryId");
            }
            if (request.getProductId() != null) {
                throw new BaseException("Khuyến mãi CATEGORY không được gắn productId");
            }
        }

        if (request.getPromotionType() == PromotionType.PRODUCT) {
            if (request.getProductId() == null) {
                throw new BaseException("Khuyến mãi PRODUCT bắt buộc có productId");
            }
            if (request.getCategoryId() != null) {
                throw new BaseException("Khuyến mãi PRODUCT không được gắn categoryId");
            }
        }
    }

    @Transactional
    public void changeCouponStatus(Long id, boolean active) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mã giảm giá"));

        coupon.setActive(active);
        couponRepository.save(coupon);
    }

    @Transactional(readOnly = true)
    public CouponCalculationResult evaluateCoupon(String code, List<CartItemRequest> items, Long userId) {
        Coupon coupon = couponRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mã giảm giá"));

        validateCouponAvailability(coupon, userId);

        List<ApplicableItem> applicableItems = resolveApplicableItems(coupon, items);
        if (applicableItems.isEmpty() && coupon.getPromotionType() != PromotionType.ORDER) {
            throw new BusinessException("Mã giảm giá không áp dụng cho sản phẩm nào trong đơn hàng");
        }

        BigDecimal orderSubtotal = calculateOrderSubtotal(items);
        if (coupon.getMinOrderValue() != null && orderSubtotal.compareTo(coupon.getMinOrderValue()) < 0) {
            throw new BusinessException("Đơn hàng không đủ số tiền tối thiểu");
        }

        BigDecimal applicableSubtotal = applicableItems.stream()
                .map(ApplicableItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal discountAmount = calculateDiscountAmount(coupon, applicableSubtotal);

        return new CouponCalculationResult(
                discountAmount,
                applicableItems.stream().map(ApplicableItem::productId).distinct().toList(),
                coupon.getPromotionType()
        );
    }

    private void assignPromotionTarget(Coupon coupon, CouponRequest request) {
        coupon.setPromotionType(request.getPromotionType());
        coupon.setCategory(null);
        coupon.setProduct(null);

        if (request.getPromotionType() == PromotionType.CATEGORY) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category", "id", request.getCategoryId()));
            coupon.setCategory(category);
        }

        if (request.getPromotionType() == PromotionType.PRODUCT) {
            Product product = productRepository.findById(request.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", "id", request.getProductId()));
            coupon.setProduct(product);
        }
    }

    private void validateCouponAvailability(Coupon coupon, Long userId) {
        if (!coupon.isActive()) {
            throw new BusinessException("Mã giảm giá không hoạt động");
        }

        if (LocalDate.now().isBefore(coupon.getStartDate()) || LocalDate.now().isAfter(coupon.getExpirationDate())) {
            throw new BusinessException("Mã giảm giá chưa được kích hoạt hoặc đã hết hạn");
        }

        if (coupon.getUsedCount() >= coupon.getUsageLimit()) {
            throw new BusinessException("Mã giảm giá đã hết");
        }

        boolean used = couponUsageRepository.existsByCouponIdAndUserId(coupon.getId(), userId);
        if (used) {
            throw new BusinessException("Bạn đã sử dụng mã giảm giá này rồi");
        }
    }

    private BigDecimal calculateOrderSubtotal(List<CartItemRequest> items) {
        return items.stream()
                .map(item -> loadProduct(item.getProductId()).getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<ApplicableItem> resolveApplicableItems(Coupon coupon, List<CartItemRequest> items) {
        List<ApplicableItem> applicableItems = new ArrayList<>();

        for (CartItemRequest item : items) {
            Product product = loadProduct(item.getProductId());
            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));

            if (coupon.getPromotionType() == PromotionType.ORDER) {
                applicableItems.add(new ApplicableItem(product.getId(), subtotal));
                continue;
            }

            if (coupon.getPromotionType() == PromotionType.CATEGORY
                    && coupon.getCategory() != null
                    && product.getCategory() != null
                    && product.getCategory().getId().equals(coupon.getCategory().getId())) {
                applicableItems.add(new ApplicableItem(product.getId(), subtotal));
            }

            if (coupon.getPromotionType() == PromotionType.PRODUCT
                    && coupon.getProduct() != null
                    && product.getId().equals(coupon.getProduct().getId())) {
                applicableItems.add(new ApplicableItem(product.getId(), subtotal));
            }
        }

        return applicableItems;
    }

    private BigDecimal calculateDiscountAmount(Coupon coupon, BigDecimal applicableAmount) {
        BigDecimal discountAmount = BigDecimal.ZERO;

        if (coupon.getDiscountType() == DiscountType.FIXED_AMOUNT) {
            discountAmount = coupon.getDiscountValue();
        } else if (coupon.getDiscountType() == DiscountType.PERCENTAGE) {
            BigDecimal percent = coupon.getDiscountValue().divide(new BigDecimal(100));
            discountAmount = percent.multiply(applicableAmount);

            if (coupon.getMaxDiscountAmount() != null && discountAmount.compareTo(coupon.getMaxDiscountAmount()) > 0) {
                discountAmount = coupon.getMaxDiscountAmount();
            }
        }

        if (discountAmount.compareTo(applicableAmount) > 0) {
            discountAmount = applicableAmount;
        }

        return discountAmount;
    }

    private Product loadProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", productId));
    }

    private CouponCalculationResponse toCalculationResponse(CouponCalculationResult result) {
        return CouponCalculationResponse.builder()
                .discountAmount(result.discountAmount())
                .appliedToItems(result.appliedToItems())
                .promotionType(result.promotionType())
                .build();
    }

    private Specification<Coupon> buildCouponSpecification(PromotionType promotionType, Long categoryId, Long productId) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (promotionType != null) {
                predicates.add(cb.equal(root.get("promotionType"), promotionType));
            }

            if (categoryId != null) {
                predicates.add(cb.equal(root.join("category", jakarta.persistence.criteria.JoinType.LEFT).get("id"), categoryId));
            }

            if (productId != null) {
                predicates.add(cb.equal(root.join("product", jakarta.persistence.criteria.JoinType.LEFT).get("id"), productId));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    public record CouponCalculationResult(BigDecimal discountAmount, List<Long> appliedToItems, PromotionType promotionType) {
    }

    private record ApplicableItem(Long productId, BigDecimal subtotal) {
    }
}
