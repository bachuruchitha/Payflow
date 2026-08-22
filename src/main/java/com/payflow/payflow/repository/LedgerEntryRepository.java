package com.payflow.payflow.repository;

import com.payflow.payflow.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
}
