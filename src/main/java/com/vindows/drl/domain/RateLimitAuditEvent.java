package com.vindows.drl.domain;

import java.time.Instant;
import java.util.UUID;

public record RateLimitAuditEvent(
    String eventId,
    String clientId,
    String resourceKey,
    Instant timestamp,
    boolean allowed,
    long remainingTokens,
    long retryAfterSeconds,
    boolean fallbackApplied) {

  public static RateLimitAuditEvent of(
      String clientId,
      String resourceKey,
      RateLimitResult result) {
    return new RateLimitAuditEvent(
        UUID.randomUUID().toString(),
        clientId,
        resourceKey,
        Instant.now(),
        result.allowed(),
        result.remainingTokens(),
        result.retryAfterSeconds(),
        result.fallbackApplied());
  }
}
