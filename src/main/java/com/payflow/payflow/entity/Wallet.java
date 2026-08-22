package com.payflow.payflow.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "balance", nullable = false)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;



    protected Wallet() {
    }


    public Wallet(UUID id, UUID userId, String currency) {
        this.id = id;
        this.userId = userId;
        this.currency = currency;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCurrency() {
        return currency;
    }

    public Integer getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }


    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }


}
