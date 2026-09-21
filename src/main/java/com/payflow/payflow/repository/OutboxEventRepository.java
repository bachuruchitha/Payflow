package com.payflow.payflow.repository;

import com.payflow.payflow.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop100ByIsPublishedFalseOrderByCreatedAtAsc();
}
