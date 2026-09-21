package com.payflow.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    private UUID transactionId;

    @Column(name = "processed_at", nullable = false, insertable = false, updatable = false)
    private Instant processedAt;


    public ProcessedEvent(UUID transactionId) {
        this.transactionId = transactionId;
    }

    protected ProcessedEvent() {
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}