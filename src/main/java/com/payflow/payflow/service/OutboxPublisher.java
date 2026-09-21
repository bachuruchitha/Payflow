package com.payflow.payflow.service;

import com.payflow.payflow.entity.OutboxEvent;
import com.payflow.payflow.repository.OutboxEventRepository;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(OutboxPublisher.class);


    public OutboxPublisher(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 5000)
    public void publishOutboxEvents() {
        List<OutboxEvent> top100ByIsPublishedFalseOrderByCreatedAtAsc = outboxEventRepository.findTop100ByIsPublishedFalseOrderByCreatedAtAsc();
        for(OutboxEvent outboxEvent:top100ByIsPublishedFalseOrderByCreatedAtAsc){
            try {
                kafkaTemplate.send("transaction-events",
                        String.valueOf(outboxEvent.getTransactionId()),
                        outboxEvent.getPayload()).get();
                outboxEvent.setIsPublished(true);
                outboxEventRepository.save(outboxEvent);
            } catch (Exception e) {
                log.error("Failed to publish kafka event for txn {}",
                        outboxEvent.getTransactionId(), e);
            }
        }
    }
}