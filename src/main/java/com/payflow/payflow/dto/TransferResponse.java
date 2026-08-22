package com.payflow.payflow.dto;

import com.payflow.payflow.entity.TransactionStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferResponse(UUID transactionId, TransactionStatus status, BigDecimal amount) {
}
