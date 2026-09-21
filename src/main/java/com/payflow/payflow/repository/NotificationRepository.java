package com.payflow.payflow.repository;

import com.payflow.payflow.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByTransactionId(UUID transactionId);
}
