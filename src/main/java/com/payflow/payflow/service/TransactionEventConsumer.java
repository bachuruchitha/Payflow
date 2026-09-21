package com.payflow.payflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payflow.dto.TransferEventPayload;
import com.payflow.payflow.entity.Notification;
import com.payflow.payflow.entity.ProcessedEvent;
import com.payflow.payflow.repository.NotificationRepository;
import com.payflow.payflow.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);


    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final NotificationRepository notificationRepository;

    public TransactionEventConsumer(ObjectMapper objectMapper, ProcessedEventRepository processedEventRepository, NotificationRepository notificationRepository) {
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.notificationRepository = notificationRepository;
    }


    @KafkaListener(topics = "transaction-events", groupId = "notification-service")
    @Transactional
    public void handle(String message) throws Exception {

        TransferEventPayload transferEventPayload=objectMapper.readValue(message, TransferEventPayload.class);
        UUID transactionId = transferEventPayload.getTransactionId();
        if(processedEventRepository.existsByTransactionId(transactionId)){
            log.info("Event already processed");
            return;
        }
        // The "work" and the idempotency record are written in the same transaction, so a
        // crash between them is impossible: either the notification and the processed-event
        // row both commit, or neither does and the event is safely redelivered.
        notificationRepository.save(new Notification(UUID.randomUUID(), transactionId,
                "Transfer " + transactionId + " completed"));
        processedEventRepository.save(new ProcessedEvent(transactionId));
    }
}
