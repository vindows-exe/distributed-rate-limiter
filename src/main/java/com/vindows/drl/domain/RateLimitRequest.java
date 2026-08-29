package com.vindows.drl.domain;

public record RateLimitRequest(
		String key,
		long capacity,
		double refillRate,
		long requestedTokens,
		FallbackStrategy fallbackStrategy) {
}
