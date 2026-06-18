package com.example.demo.service;

import com.example.demo.config.CacheConfig;
import com.example.demo.dto.request.CategoryCreationRequest;
import com.example.demo.dto.request.CategoryUpdateRequest;
import com.example.demo.dto.response.CategoryResponse;
import com.example.demo.entity.Category;
import com.example.demo.exception.BusinessException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.mapper.CategoryMapper;
import com.example.demo.repository.CategoryRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class CategoryService {
    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    public CategoryService(CategoryRepository categoryRepository, CategoryMapper categoryMapper) {
        this.categoryRepository = categoryRepository;
        this.categoryMapper = categoryMapper;
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    public CategoryResponse create(CategoryCreationRequest request) {
        if (categoryRepository.existsByName(request.getName())) {
            throw new BusinessException("Category with name " + request.getName() + " already exists");
        }
        Category category = categoryMapper.createCategoryFromDto(request);
        return  categoryMapper.toResponse(categoryRepository.save(category));
    }

    @Transactional(readOnly = true)
    @Cacheable(CacheConfig.CATEGORIES)
    public List<CategoryResponse> getAll() {
        List<Category> categories = categoryRepository.findAll();

        return categories.stream().map(categoryMapper::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CategoryResponse getById(Long id) {
        Category category = categoryRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Category", "id", id));
        return categoryMapper.toResponse(category);
    }

    // product responses embed the category, so they are refreshed too
    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.CATEGORIES, CacheConfig.PUBLIC_PRODUCTS}, allEntries = true)
    public CategoryResponse updateById(Long id, CategoryUpdateRequest request) {
        Category category = categoryRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Category", "id", id));
        if (!category.getName().equals(request.getName())) {
            if (categoryRepository.existsByName(request.getName())) {
                throw new BusinessException("Category with name " + request.getName() + " already exists");
            }
        }
        categoryMapper.updateCategoryFromDto(request, category);
        return categoryMapper.toResponse(categoryRepository.save(category));
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.CATEGORIES, CacheConfig.PUBLIC_PRODUCTS}, allEntries = true)
    public CategoryResponse deleteById(Long id) {
        Category category = categoryRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Category", "id", id));
        categoryRepository.delete(category);
        return categoryMapper.toResponse(category);
    }
}
