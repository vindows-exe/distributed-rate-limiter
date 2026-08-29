package com.vindows.drl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.vindows.drl.domain.FallbackStrategy;
import com.vindows.drl.domain.RateLimitRequest;
import com.vindows.drl.domain.RateLimitResult;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "rawtypes", "unchecked" })
class RateLimiterServiceTest {

  @Mock
  private StringRedisTemplate redisTemplate;
  @Mock
  private RedisScript<List> tokenBucketScript;

  private RateLimiterService rateLimiterService;

  private final Clock clock = Clock.systemUTC();

  @BeforeEach
  void setUp() {
    rateLimiterService = new RateLimiterService(redisTemplate, tokenBucketScript, clock);
  }

  @Test
  void shouldAllowRequestAndMapResultCorrectly() {
    when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(List.of(1L, 4L, 0L));

    RateLimitResult result = rateLimiterService.isAllowed(request());

    assertThat(result.allowed()).isTrue();
    assertThat(result.remainingTokens()).isEqualTo(4L);
    assertThat(result.retryAfterSeconds()).isZero();
    assertThat(result.fallbackApplied()).isFalse();
  }

  @Test
  void shouldRejectRequestWhenLimitExceeded() {
    when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(List.of(0L, 0L, 5L));

    RateLimitResult result = rateLimiterService.isAllowed(request());

    assertThat(result.allowed()).isFalse();
    assertThat(result.remainingTokens()).isZero();
    assertThat(result.retryAfterSeconds()).isEqualTo(5L);
    assertThat(result.fallbackApplied()).isFalse();
  }

  @Test
  void shouldFallbackToOpenWhenRedisFailsAndStrategyIsFailOpen() {
    when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenThrow(mock(RedisSystemException.class));

    RateLimitRequest request = request(FallbackStrategy.FAIL_OPEN);
    RateLimitResult result = rateLimiterService.isAllowed(request);

    assertThat(result.allowed()).isTrue();
    assertThat(result.remainingTokens()).isEqualTo(request.capacity());
    assertThat(result.retryAfterSeconds()).isZero();
    assertThat(result.fallbackApplied()).isTrue();
  }

  @Test
  void shouldFallbackToClosedWhenRedisFailsAndStrategyIsFailClosed() {
    when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenThrow(mock(RedisConnectionFailureException.class));

    RateLimitRequest request = request(FallbackStrategy.FAIL_CLOSED);
    RateLimitResult result = rateLimiterService.isAllowed(request);

    assertThat(result.allowed()).isFalse();
    assertThat(result.remainingTokens()).isZero();
    assertThat(result.retryAfterSeconds()).isEqualTo(60L);
    assertThat(result.fallbackApplied()).isTrue();
  }

  @Test
  void shouldRejectInvalidRequestParameters() {
    RateLimitRequest nullKey = new RateLimitRequest(null, 5, 1.0, 1, FallbackStrategy.FAIL_OPEN);
    RateLimitRequest emptyKey = new RateLimitRequest("", 5, 1.0, 1, FallbackStrategy.FAIL_OPEN);
    RateLimitRequest zeroCapacity = new RateLimitRequest("k", 0, 1.0, 1, FallbackStrategy.FAIL_OPEN);
    RateLimitRequest negativeCapacity = new RateLimitRequest("k", -1, 1.0, 1, FallbackStrategy.FAIL_OPEN);
    RateLimitRequest zeroTokens = new RateLimitRequest("k", 5, 1.0, 0, FallbackStrategy.FAIL_OPEN);
    RateLimitRequest negativeTokens = new RateLimitRequest("k", 5, 1.0, -1, FallbackStrategy.FAIL_OPEN);

    assertThatThrownBy(() -> rateLimiterService.isAllowed(nullKey)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rateLimiterService.isAllowed(emptyKey)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rateLimiterService.isAllowed(zeroCapacity)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rateLimiterService.isAllowed(negativeCapacity))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rateLimiterService.isAllowed(zeroTokens)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rateLimiterService.isAllowed(negativeTokens)).isInstanceOf(IllegalArgumentException.class);
  }

  private RateLimitRequest request() {
    return request(FallbackStrategy.FAIL_OPEN);
  }

  private RateLimitRequest request(FallbackStrategy fallbackStrategy) {
    return new RateLimitRequest("client-1", 5, 1.0, 1, fallbackStrategy);
  }
}
