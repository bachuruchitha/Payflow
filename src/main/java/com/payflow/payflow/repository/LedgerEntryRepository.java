package com.payflow.payflow.repository;

import com.payflow.payflow.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query("SELECT COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE -e.amount END), 0) " +
            "FROM LedgerEntry e WHERE e.walletId = :walletId")
    BigDecimal computeBalanceFromLedger(@Param("walletId") UUID walletId);

}
