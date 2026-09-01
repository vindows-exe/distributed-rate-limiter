package com.vindows.drl.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vindows.drl.domain.RateLimitAuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Component
public class RateLimitAuditProducer {

  private static final Logger log = LoggerFactory.getLogger(RateLimitAuditProducer.class);
  public static final String AUDIT_TOPIC = "rate-limit-audits";

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  public RateLimitAuditProducer(KafkaTemplate<String, String> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public CompletableFuture<SendResult<String, String>> sendAuditEvent(RateLimitAuditEvent event) {
    Objects.requireNonNull(event, "RateLimitAuditEvent must not be null");

    String payload;
    try {
      payload = objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException ex) {
      log.error("Failed to serialize audit event for client '{}', eventId '{}'",
          event.clientId(), event.eventId(), ex);
      return CompletableFuture.failedFuture(ex);
    }

    return kafkaTemplate.send(AUDIT_TOPIC, event.clientId(), payload)
        .whenComplete((result, ex) -> {
          if (ex != null) {
            log.error("Failed to publish audit event to Kafka for client '{}', eventId '{}'",
                event.clientId(), event.eventId(), ex);
          } else {
            log.debug("Published audit event '{}' to partition {} with offset {}",
                event.eventId(),
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
          }
        });
  }
}
