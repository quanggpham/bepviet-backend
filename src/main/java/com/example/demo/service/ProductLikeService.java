package com.example.demo.service;

import com.example.demo.dto.response.ProductResponse;
import com.example.demo.entity.Product;
import com.example.demo.entity.ProductLike;
import com.example.demo.entity.User;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.mapper.ProductMapper;
import com.example.demo.repository.ProductLikeRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProductLikeService {

    private final ProductLikeRepository productLikeRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ProductMapper productMapper;

    @Transactional
    public ProductLikeSummary likeProduct(Long productId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Khong tim thay nguoi dung"));
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Khong tim thay san pham"));

        Optional<ProductLike> existing = productLikeRepository.findByUserAndProduct(user, product);
        if (existing.isEmpty()) {
            ProductLike like = ProductLike.builder()
                    .user(user)
                    .product(product)
                    .build();
            productLikeRepository.save(like);
        }

        return buildSummary(productId, true);
    }

    @Transactional
    public ProductLikeSummary unlikeProduct(Long productId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Khong tim thay nguoi dung"));
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Khong tim thay san pham"));

        productLikeRepository.findByUserAndProduct(user, product)
                .ifPresent(productLikeRepository::delete);

        return buildSummary(productId, false);
    }

    @Transactional(readOnly = true)
    public boolean isLiked(Long productId, Long userId) {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Khong tim thay san pham");
        }
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("Khong tim thay nguoi dung");
        }
        return productLikeRepository.existsByUserIdAndProductId(userId, productId);
    }

    @Transactional(readOnly = true)
    public long getLikeCount(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Khong tim thay san pham");
        }
        return productLikeRepository.countByProductId(productId);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getLikedProducts(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("Khong tim thay nguoi dung");
        }
        List<ProductLike> likes = productLikeRepository.findByUserIdWithProduct(userId);
        Map<Long, Long> likeCounts = productLikeRepository.countByProductIds(
                likes.stream().map(pl -> pl.getProduct().getId()).toList());
        return likes.stream()
                .map(pl -> {
                    ProductResponse response = productMapper.toResponse(pl.getProduct());
                    response.setLikeCount(likeCounts.getOrDefault(pl.getProduct().getId(), 0L));
                    response.setLiked(true);
                    return response;
                })
                .toList();
    }

    private ProductLikeSummary buildSummary(Long productId, boolean liked) {
        return new ProductLikeSummary(liked, productLikeRepository.countByProductId(productId));
    }

    public record ProductLikeSummary(boolean liked, long likeCount) {
    }
}
