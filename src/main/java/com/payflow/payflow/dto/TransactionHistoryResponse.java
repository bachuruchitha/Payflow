package com.payflow.payflow.dto;

import com.payflow.payflow.entity.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionHistoryResponse(UUID transactionId, Direction direction, UUID counterPartyWalletId,
                                         BigDecimal amount, TransactionStatus status, Instant createdAt) {
}
