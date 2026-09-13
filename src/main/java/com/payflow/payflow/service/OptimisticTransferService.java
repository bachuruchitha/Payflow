package com.payflow.payflow.service;

import com.payflow.payflow.dto.TransferResponse;
import com.payflow.payflow.exception.TransferConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Outer half of the optimistic strategy: retries OptimisticTransferExecutor when it loses
 * a version check. It does NOT replay idempotency keys. Call it through
 * TransferService.transferOptimistic, which does.
 *
 * Deliberately NOT @Transactional. Each executor call goes through the executor's proxy and
 * gets a brand-new transaction and persistence context, so a retry re-reads the wallets with
 * their current versions. If this class joined an outer transaction, every retry would reuse
 * the same aborted one and fail the same way.
 */
@Service
public class OptimisticTransferService {

    private static final Logger log = LoggerFactory.getLogger(OptimisticTransferService.class);

    // Every conflict means some other transfer on the same wallet committed, so the system as
    // a whole is making progress. 3 attempts absorbs ordinary contention. A wallet that keeps
    // losing past that is a hot row, where optimistic locking is the wrong tool: fail fast
    // instead of holding the request thread in a retry loop.
    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 20;

    private final OptimisticTransferExecutor executor;

    public OptimisticTransferService(OptimisticTransferExecutor executor) {
        this.executor = executor;
    }

    // Only ObjectOptimisticLockingFailureException is caught, because it's the one failure a
    // retry can fix: another transaction changed our wallet between our read and our commit.
    // Business rejections (InsufficientBalance, SelfTransferNotAllowed, WalletNotFound) aren't
    // caught, since the same request against the same committed data fails the same way every
    // time. They leave on attempt 1. A duplicate Idempotency-Key (DataIntegrityViolation) also
    // passes straight through, to the caller that replays the winner.
    public TransferResponse transfer(String idempotencyKey, UUID senderId, UUID receiverWalletId, BigDecimal amount) {
        ObjectOptimisticLockingFailureException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return executor.executeTransfer(idempotencyKey, senderId, receiverWalletId, amount);
            } catch (ObjectOptimisticLockingFailureException e) {
                lastConflict = e;
                log.debug("Optimistic conflict on attempt {}/{} for transfer {}", attempt, MAX_ATTEMPTS, idempotencyKey);
                if (attempt < MAX_ATTEMPTS) {
                    backoff(attempt, e);
                }
            }
        }
        // Every attempt rolled back, so nothing was committed and a client retry is safe.
        throw new TransferConflictException(MAX_ATTEMPTS, lastConflict);
    }

    // Random wait so the transfers that just collided don't all retry in lockstep and collide again.
    private static void backoff(int attempt, ObjectOptimisticLockingFailureException conflict) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, BACKOFF_BASE_MS * attempt + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TransferConflictException(attempt, conflict);
        }
    }
}
