package com.payflow.payflow.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        UUID id,
        UUID userId,
        String currency,
        Instant createdAt,
        BigDecimal balance
        // note: no version field — internal concurrency detail, not the caller's business
) {}