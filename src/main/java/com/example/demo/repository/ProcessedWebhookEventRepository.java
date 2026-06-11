package com.example.demo.repository;

import com.example.demo.entity.ProcessedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedWebhookEventRepository extends JpaRepository<ProcessedWebhookEvent, String> {

    /**
     * Returns 1 if this event id is new, 0 if it was already recorded.
     * A concurrent duplicate blocks on the primary key until the first transaction finishes.
     */
    @Modifying
    @Query(value = "INSERT INTO processed_webhook_events (event_id, provider, event_type, processed_at) " +
            "VALUES (:eventId, :provider, :eventType, now()) ON CONFLICT (event_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId,
                       @Param("provider") String provider,
                       @Param("eventType") String eventType);
}
