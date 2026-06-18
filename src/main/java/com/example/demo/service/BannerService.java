package com.example.demo.service;

import com.example.demo.config.CacheConfig;
import com.example.demo.dto.request.BannerReorderRequest;
import com.example.demo.dto.request.BannerRequest;
import com.example.demo.dto.response.BannerResponse;
import com.example.demo.dto.response.PageResponse;
import com.example.demo.entity.Banner;
import com.example.demo.enums.BannerBadgeIcon;
import com.example.demo.exception.BaseException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.mapper.BannerMapper;
import com.example.demo.repository.BannerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BannerService {

    private final BannerRepository bannerRepository;
    private final BannerMapper bannerMapper;

    @Transactional(readOnly = true)
    @Cacheable(CacheConfig.ACTIVE_BANNERS)
    public List<BannerResponse> getActiveBanners() {
        return bannerRepository.findActiveBanners(LocalDateTime.now())
                .stream()
                .map(bannerMapper::toBannerResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<BannerResponse> getAllBanners(Pageable pageable) {
        int validPageSize = Math.min(pageable.getPageSize(), 50);
        Pageable normalizedPageable = PageRequest.of(pageable.getPageNumber(), validPageSize, pageable.getSort());

        Page<Banner> pageData = bannerRepository.findAllByOrderByDisplayOrderAscIdAsc(normalizedPageable);
        List<BannerResponse> items = pageData.getContent().stream()
                .map(bannerMapper::toBannerResponse)
                .toList();

        return PageResponse.<BannerResponse>builder()
                .items(items)
                .pageSize(pageData.getSize())
                .totalPages(pageData.getTotalPages())
                .totalElements(pageData.getTotalElements())
                .currentPage(pageData.getNumber() + 1)
                .build();
    }

    @Transactional(readOnly = true)
    public BannerResponse getBannerById(Long id) {
        return bannerMapper.toBannerResponse(findBanner(id));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.ACTIVE_BANNERS, allEntries = true)
    public BannerResponse createBanner(BannerRequest request) {
        validateBannerSchedule(request);

        Banner banner = bannerMapper.toBanner(request);
        normalizeBannerDefaults(banner);

        return bannerMapper.toBannerResponse(bannerRepository.save(banner));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.ACTIVE_BANNERS, allEntries = true)
    public BannerResponse updateBanner(Long id, BannerRequest request) {
        validateBannerSchedule(request);

        Banner banner = findBanner(id);
        bannerMapper.updateBannerFromRequest(request, banner);
        normalizeBannerDefaults(banner);

        return bannerMapper.toBannerResponse(bannerRepository.save(banner));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.ACTIVE_BANNERS, allEntries = true)
    public void deleteBanner(Long id) {
        Banner banner = findBanner(id);
        bannerRepository.delete(banner);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.ACTIVE_BANNERS, allEntries = true)
    public void changeBannerStatus(Long id, boolean active) {
        Banner banner = findBanner(id);
        banner.setActive(active);
        bannerRepository.save(banner);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.ACTIVE_BANNERS, allEntries = true)
    public List<BannerResponse> reorderBanners(List<BannerReorderRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new BaseException("Danh sách sắp xếp banner không được để trống");
        }

        Set<Long> uniqueIds = requests.stream()
                .map(BannerReorderRequest::getId)
                .collect(java.util.stream.Collectors.toSet());

        if (uniqueIds.size() != requests.size()) {
            throw new BaseException("Danh sách sắp xếp banner có id bị trùng");
        }

        Map<Long, Banner> bannersById = new LinkedHashMap<>();
        bannerRepository.findAllById(uniqueIds)
                .forEach(banner -> bannersById.put(banner.getId(), banner));

        if (bannersById.size() != requests.size()) {
            Long missingId = requests.stream()
                    .map(BannerReorderRequest::getId)
                    .filter(id -> !bannersById.containsKey(id))
                    .findFirst()
                    .orElse(null);
            throw new ResourceNotFoundException("Banner", "id", missingId);
        }

        for (BannerReorderRequest request : requests) {
            Banner banner = bannersById.get(request.getId());
            banner.setDisplayOrder(request.getDisplayOrder());
        }

        bannerRepository.saveAll(bannersById.values());

        return requests.stream()
                .map(BannerReorderRequest::getId)
                .map(bannersById::get)
                .map(bannerMapper::toBannerResponse)
                .toList();
    }

    private Banner findBanner(Long id) {
        return bannerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Banner", "id", id));
    }

    private void validateBannerSchedule(BannerRequest request) {
        if (request.getStartDate() != null && request.getEndDate() != null
                && request.getStartDate().isAfter(request.getEndDate())) {
            throw new BaseException("Ngày bắt đầu không được lớn hơn ngày kết thúc");
        }
    }

    private void normalizeBannerDefaults(Banner banner) {
        if (banner.getDisplayOrder() == null) {
            banner.setDisplayOrder(0);
        }
        if (banner.getBadgeIcon() == null) {
            banner.setBadgeIcon(BannerBadgeIcon.NONE);
        }
    }
}
