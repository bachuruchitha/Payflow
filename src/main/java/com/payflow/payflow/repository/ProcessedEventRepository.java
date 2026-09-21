package com.payflow.payflow.repository;

import com.payflow.payflow.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID > {

    boolean existsByTransactionId(UUID transactionId);
}
