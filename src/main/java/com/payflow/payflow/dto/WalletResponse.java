package com.payflow.payflow.dto;

import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        UUID id,
        UUID userId,
        String currency,
        Instant createdAt
        // note: no version field — internal concurrency detail, not the caller's business
) {}