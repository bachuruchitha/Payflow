package com.payflow.payflow.repository;

import com.payflow.payflow.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Page<Transaction> findByFromWalletIdOrToWalletId(UUID fromWalletId, UUID toWalletId, Pageable pageable);
}
