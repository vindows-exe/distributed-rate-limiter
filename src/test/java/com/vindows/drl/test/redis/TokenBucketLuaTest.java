package com.vindows.drl.test.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Isolated Testcontainers integration test for the Token Bucket Lua script
 * ({@code scripts/token_bucket.lua}).
 *
 * <p>
 * The script is evaluated directly against a real Redis 7 instance - no
 * service layer, no domain logic. The Redis state is flushed before every test.
 */
@Testcontainers
@SpringBootTest
class TokenBucketLuaTest {

  @Container
  @SuppressWarnings("rawtypes")
  static final GenericContainer REDIS = new GenericContainer(DockerImageName.parse("redis:7-alpine"))
      .withExposedPorts(6379);

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired
  private StringRedisTemplate redisTemplate;

  @Autowired
  private RedisScript<List> tokenBucketScript;

  @BeforeEach
  void setUp() {
    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @AfterEach
  void tearDown() {
    if (redisTemplate != null) {
      redisTemplate.getConnectionFactory().getConnection().close();
    }
  }

  @Test
  void shouldAllowRequestsWithinCapacity() {
    String key = "bucket:capacity";

    for (int i = 0; i < 5; i++) {
      List<Long> result = execute(key, "5", "1", String.valueOf(1000L));
      assertThat(result).isNotNull();
      assertThat(result.get(0)).isEqualTo(1L); // allowed
    }

    assertThat(redisTemplate.hasKey(key)).isTrue();
  }

  @Test
  void shouldRejectRequestWhenCapacityExhausted() {
    String key = "bucket:exhausted";

    for (int i = 0; i < 5; i++) {
      execute(key, "5", "1", String.valueOf(1000L));
    }

    List<Long> rejected = execute(key, "5", "1", String.valueOf(1000L));

    assertThat(rejected.get(0)).isEqualTo(0L); // allowed = 0
    assertThat(rejected.get(2)).isGreaterThan(0L); // retry_after > 0
  }

  @Test
  void shouldRefillTokensOverTime() {
    String key = "bucket:refill";

    for (int i = 0; i < 5; i++) {
      execute(key, "5", "1", String.valueOf(1000L));
    }
    List<Long> rejected = execute(key, "5", "1", String.valueOf(1000L));
    assertThat(rejected.get(0)).isEqualTo(0L);

    // 2 seconds later: 2 tokens should have been refilled.
    List<Long> refilled = execute(key, "5", "1", String.valueOf(3000L));

    assertThat(refilled.get(0)).isEqualTo(1L); // allowed again
    assertThat(refilled.get(1)).isEqualTo(1L); // 1 token remaining
  }

  @Test
  void shouldSetTtlOnKey() {
    String key = "bucket:ttl";

    execute(key, "5", "1", String.valueOf(1000L));

    Long ttl = redisTemplate.getExpire(key);
    assertThat(ttl).isGreaterThan(0L);
  }

  private List<Long> execute(String key, String capacity, String refillRate, String now) {
    return redisTemplate.execute(
        tokenBucketScript,
        List.of(key),
        capacity,
        refillRate,
        now);
  }
}
