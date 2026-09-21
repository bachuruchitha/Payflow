package com.payflow.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "transaction_id",nullable = false)
    private UUID transactionId;

    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "is_published",nullable = false)
    private Boolean isPublished;

    @Column(name = "created_at",nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public OutboxEvent(UUID id, UUID transactionId, String payload) {
        this.id = id;
        this.transactionId = transactionId;
        this.payload = payload;
        this.isPublished = false;
    }

    protected OutboxEvent(){

    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getPayload() {
        return payload;
    }

    public Boolean getIsPublished() {
        return isPublished;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setIsPublished(Boolean published) {
        isPublished = published;
    }
}
