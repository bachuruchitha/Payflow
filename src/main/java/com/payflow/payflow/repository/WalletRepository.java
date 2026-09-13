package com.payflow.payflow.repository;

import com.payflow.payflow.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {
    Optional<Wallet> findByUserId(UUID userId);

    // Scalar projection on purpose: resolves userId -> walletId without loading the
    // Wallet entity (or its balance) into the persistence context, so the very first
    // touch of the wallet row is the locked fetch below.
    @Query("select w.id from Wallet w where w.userId = :userId")
    Optional<UUID> findWalletIdByUserId(@Param("userId") UUID userId);

    // PESSIMISTIC_WRITE issues SELECT ... FOR UPDATE, blocking any other transaction
    // that tries to lock this same row (as sender or receiver) until this one commits
    // or rolls back. Callers must acquire this lock BEFORE reading the balance they'll
    // base a decision on -- see TransferExecutor for why.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") UUID id);
}
