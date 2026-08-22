package com.payflow.payflow.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;


@Entity
@Table(name = "transaction")
public class Transaction {

    @Id
    UUID transactionId;

    @Column(name = "from_wallet_id", nullable = false)
    UUID fromWalletId;

    @Column(name = "to_wallet_id", nullable = false)
    UUID toWalletId;

    @Column(name = "amount", nullable = false)
    BigDecimal amount;


    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    TransactionStatus status;


    public void setStatus(TransactionStatus transactionStatus) {
        this.status = transactionStatus;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public UUID getToWalletId() {
        return toWalletId;
    }

    public UUID getFromWalletId() {
        return fromWalletId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public Transaction(UUID transactionId, UUID fromWalletId, UUID toWalletId,
                       BigDecimal amount, TransactionStatus status) {
        this.transactionId = transactionId;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.status = status;
    }

    protected Transaction() { }
}
