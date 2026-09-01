package com.vindows.drl.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vindows.drl.domain.RateLimitAuditEvent;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RateLimitAuditProducerIntegrationTest {

  @Container
  static final KafkaContainer KAFKA = new KafkaContainer(
      DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

  @DynamicPropertySource
  static void overrideKafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.data.redis.host", () -> "localhost");
    registry.add("spring.data.redis.port", () -> "6379");
  }

  @Autowired
  private RateLimitAuditProducer producer;

  // Autark instanziiert ohne Spring-Context-Abhängigkeit
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  private Consumer<String, String> testConsumer;

  @BeforeEach
  void setUp() {
    Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
        KAFKA.getBootstrapServers(),
        "test-audit-group-" + UUID.randomUUID(),
        "true");
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProps);
    testConsumer = consumerFactory.createConsumer();
    testConsumer.subscribe(Collections.singletonList(RateLimitAuditProducer.AUDIT_TOPIC));
  }

  @AfterEach
  void tearDown() {
    if (testConsumer != null) {
      testConsumer.close();
    }
  }

  @Test
  void shouldPublishAuditEventToKafkaTopic() throws Exception {
    RateLimitAuditEvent event = new RateLimitAuditEvent(
        UUID.randomUUID().toString(),
        "client-42",
        "resource:user-1",
        Instant.now(),
        true,
        7L,
        0L,
        false);

    producer.sendAuditEvent(event).get();

    ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(testConsumer, Duration.ofSeconds(10));
    assertThat(records.count()).isGreaterThanOrEqualTo(1);

    ConsumerRecord<String, String> receivedRecord = records.iterator().next();
    assertThat(receivedRecord.key()).isEqualTo("client-42");

    RateLimitAuditEvent deserializedEvent = objectMapper.readValue(
        receivedRecord.value(),
        RateLimitAuditEvent.class);

    assertThat(deserializedEvent.eventId()).isEqualTo(event.eventId());
    assertThat(deserializedEvent.clientId()).isEqualTo("client-42");
    assertThat(deserializedEvent.allowed()).isTrue();
    assertThat(deserializedEvent.remainingTokens()).isEqualTo(7L);
  }
}
