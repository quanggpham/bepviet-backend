package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One row per webhook event id that has been handled. Rows are inserted with
 * ON CONFLICT DO NOTHING, so a redelivered event is detected atomically.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "processed_webhook_events")
public class ProcessedWebhookEvent {

    @Id
    @Column(name = "event_id", length = 100)
    private String eventId;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;
}
