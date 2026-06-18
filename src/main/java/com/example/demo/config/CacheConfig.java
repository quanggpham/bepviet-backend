package com.example.demo.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * In-process caches for public catalog reads. Admin writes evict them; the TTLs bound how stale
 * data changed elsewhere can get (stock after orders, like counts, review ratings, banner schedules).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CATEGORIES = "categories";
    public static final String ACTIVE_BANNERS = "activeBanners";
    public static final String PUBLIC_PRODUCTS = "publicProducts";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(CATEGORIES, Caffeine.newBuilder()
                .maximumSize(100).expireAfterWrite(Duration.ofMinutes(10)).build());
        manager.registerCustomCache(ACTIVE_BANNERS, Caffeine.newBuilder()
                .maximumSize(10).expireAfterWrite(Duration.ofSeconds(60)).build());
        manager.registerCustomCache(PUBLIC_PRODUCTS, Caffeine.newBuilder()
                .maximumSize(1_000).expireAfterWrite(Duration.ofSeconds(30)).build());
        // Evictions inside a transaction run after commit, so a reader cannot re-cache the old rows.
        return new TransactionAwareCacheManagerProxy(manager);
    }
}
