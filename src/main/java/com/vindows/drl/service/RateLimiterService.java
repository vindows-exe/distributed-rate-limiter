package com.vindows.drl.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.vindows.drl.domain.FallbackStrategy;
import com.vindows.drl.domain.RateLimitRequest;
import com.vindows.drl.domain.RateLimitResult;

@Service
public class RateLimiterService {

  private static final Logger log = org.slf4j.LoggerFactory.getLogger(RateLimiterService.class);

  private final StringRedisTemplate redisTemplate;
  private final RedisScript<List> tokenBucketScript;
  private final Clock clock;

  public RateLimiterService(StringRedisTemplate redisTemplate, RedisScript<List> tokenBucketScript, Clock clock) {
    this.redisTemplate = redisTemplate;
    this.tokenBucketScript = tokenBucketScript;
    this.clock = clock;
  }

  public RateLimitResult isAllowed(RateLimitRequest request) {
    validateRequest(request);

    try {
      long now_ms = Instant.now(clock).toEpochMilli();

      List<?> rawResult = redisTemplate.execute(
          tokenBucketScript,
          Collections.singletonList(request.key()),
          String.valueOf(request.capacity()),
          String.valueOf(request.refillRate()),
          String.valueOf(now_ms),
          String.valueOf(request.requestedTokens()));

      return mapToResult(rawResult);

    } catch (RuntimeException ex) {
      log.warn("Redis rate limiter execution failed for key '{}‘. Applying fallback strategy: {}",
          request.key(), request.fallbackStrategy(), ex);
      return handleFallback(request);
    }
  }

  private void validateRequest(RateLimitRequest request) {
    Objects.requireNonNull(request, "RateLimitRequest must not be null");

    if (request.key() == null || request.key().trim().isEmpty()) {
      throw new IllegalArgumentException("Key must not be empty or null");
    }

    if (request.capacity() <= 0) {
      throw new IllegalArgumentException("Capacity must be greater than 0");
    }

    if (request.refillRate() <= 0) {
      throw new IllegalArgumentException("Refill rate must be greater than 0");
    }

    if (request.requestedTokens() <= 0) {
      throw new IllegalArgumentException("Requested Tokens must be greater than 0");
    }

    Objects.requireNonNull(request.fallbackStrategy(), "FallbackStrategy must not be null");
  }

  private RateLimitResult mapToResult(List<?> rawResult) {
    if (rawResult == null || rawResult.size() < 3) {
      throw new IllegalStateException("Invalid response from Redis token bucket script");
    }

    long allowedFlag = ((Number) rawResult.get(0)).longValue();
    long remainingTokens = ((Number) rawResult.get(1)).longValue();
    long retryAfter = ((Number) rawResult.get(2)).longValue();

    return new RateLimitResult(
        allowedFlag == 1L,
        remainingTokens,
        retryAfter,
        false);
  }

  private RateLimitResult handleFallback(RateLimitRequest request) {
    if (request.fallbackStrategy() == FallbackStrategy.FAIL_OPEN) {
      return new RateLimitResult(true, request.capacity(), 0L, true);
    }
    return new RateLimitResult(false, 0L, 60L, true);
  }

}
