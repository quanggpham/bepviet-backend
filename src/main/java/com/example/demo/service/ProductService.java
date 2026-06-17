package com.example.demo.service;

import com.example.demo.dto.request.ProductCreationRequest;
import com.example.demo.dto.request.ProductSearchRequest;
import com.example.demo.dto.request.ProductUpdateRequest;
import com.example.demo.dto.response.PageResponse;
import com.example.demo.dto.response.ProductResponse;
import com.example.demo.entity.Category;
import com.example.demo.entity.Product;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.mapper.ProductMapper;
import com.example.demo.repository.CategoryRepository;
import com.example.demo.repository.ProductLikeRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.specification.ProductSpecification;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.entity.ProductStats;
import com.example.demo.repository.ProductStatsRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final ProductMapper productMapper;
    private final CategoryRepository categoryRepository;
    private final ProductLikeRepository productLikeRepository;
    private final ProductStatsRepository productStatsRepository;

    @Transactional
    public ProductResponse create(@Valid ProductCreationRequest request) {
        Product product = productMapper.toEntity(request);

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category", "id", request.getCategoryId()));
        product.setCategory(category);
        product.setIsActive(true);
        return productMapper.toResponse(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category", "id", request.getCategoryId()));
        productMapper.updateProductFromDto(request, product);
        product.setCategory(category);
        return productMapper.toResponse(productRepository.save(product));
    }

    @Transactional
    public ProductResponse delete(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
        productRepository.delete(product);
        return productMapper.toResponse(product);
    }

    public ProductResponse findById(Long id, Long userId) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
        return toResponses(List.of(product), userId).get(0);
    }

    public PageResponse<ProductResponse> search(ProductSearchRequest request, Pageable pageable, Long userId) {
        int validatedSize = Math.min(pageable.getPageSize(), 50);

        Pageable finalPageable = PageRequest.of(
                pageable.getPageNumber(),
                validatedSize,
                pageable.getSort()
        );

        Specification<Product> spec = Specification.where(null);
        if (request.getCategoryId() != null) {
            spec = spec.and(ProductSpecification.hasCategory(request.getCategoryId()));
        }
        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            spec = spec.and(ProductSpecification.hasName(request.getName().trim()));
        }
        if (request.getMinPrice() != null || request.getMaxPrice() != null) {
            spec = spec.and(ProductSpecification.hasPrice(request.getMinPrice(), request.getMaxPrice()));
        }

        Page<Product> pageData = productRepository.findAll(spec, finalPageable);
        List<ProductResponse> response = toResponses(pageData.getContent(), userId);

        return PageResponse.<ProductResponse>builder()
                .pageSize(pageData.getSize())
                .currentPage(pageData.getNumber() + 1)
                .totalElements(pageData.getTotalElements())
                .totalPages(pageData.getTotalPages())
                .items(response)
                .build();
    }

    /** Like counts, the viewer's likes and review stats are loaded for the whole page at once, not per product. */
    private List<ProductResponse> toResponses(List<Product> products, Long userId) {
        if (products.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = products.stream().map(Product::getId).toList();
        Map<Long, Long> likeCounts = productLikeRepository.countByProductIds(productIds);
        Set<Long> likedIds = userId == null ? Set.of() : productLikeRepository.findLikedProductIds(userId, productIds);
        Map<Long, ProductStats> statsById = findStats(productIds);

        return products.stream()
                .map(product -> {
                    ProductResponse response = productMapper.toResponse(product);
                    response.setLikeCount(likeCounts.getOrDefault(product.getId(), 0L));
                    response.setLiked(likedIds.contains(product.getId()));
                    ProductStats stats = statsById.get(product.getId());
                    if (stats != null) {
                        response.setAvgRating(stats.getAvgRating());
                        response.setTotalReviews(stats.getTotalReviews());
                    } else {
                        response.setAvgRating(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                        response.setTotalReviews(0L);
                    }
                    return response;
                })
                .toList();
    }

    private Map<Long, ProductStats> findStats(List<Long> productIds) {
        try {
            return productStatsRepository.findAllById(productIds).stream()
                    .collect(Collectors.toMap(ProductStats::getProductId, Function.identity()));
        } catch (Exception e) {
            // Fallback to defaults if stats data is corrupted
            return Map.of();
        }
    }
}
