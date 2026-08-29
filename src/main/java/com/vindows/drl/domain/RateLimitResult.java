package com.vindows.drl.domain;

public record RateLimitResult(
		boolean allowed,
		long remainingTokens,
		long retryAfterSeconds,
		boolean fallbackApplied) {
}
