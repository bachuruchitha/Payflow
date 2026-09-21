package com.payflow.payflow.dto;


import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

// A plain class just for JSON serialization — not a JPA entity.
public class TransferEventPayload {
    private UUID transactionId;

    private BigDecimal amount;

    private UUID fromWalletId;

    private UUID toWalletId;

    public TransferEventPayload( @JsonProperty("transactionId") UUID transactionId,
                                 @JsonProperty("amount") BigDecimal amount,
                                 @JsonProperty("fromWalletId") UUID fromWalletId,
                                 @JsonProperty("toWalletId") UUID toWalletId) {
        this.transactionId = transactionId;
        this.amount = amount;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public UUID getFromWalletId() {
        return fromWalletId;
    }

    public UUID getToWalletId() {
        return toWalletId;
    }
}
