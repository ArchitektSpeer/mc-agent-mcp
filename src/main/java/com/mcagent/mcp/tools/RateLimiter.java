package com.mcagent.mcp.tools;

import java.util.function.LongSupplier;

/**
 * Token bucket: {@code burst} calls at once, refilled at {@code perSecond}. Thread-safe.
 * The clock is injectable so the refill maths can be tested without sleeping.
 */
public final class RateLimiter {
	private final double perSecond;
	private final double burst;
	private final LongSupplier nanoClock;
	private double tokens;
	private long last;

	public RateLimiter(double perSecond, double burst, LongSupplier nanoClock) {
		if (perSecond <= 0 || burst < 1) {
			throw new IllegalArgumentException("perSecond must be > 0 and burst >= 1");
		}
		this.perSecond = perSecond;
		this.burst = burst;
		this.nanoClock = nanoClock;
		this.tokens = burst;
		this.last = nanoClock.getAsLong();
	}

	public RateLimiter(double perSecond, double burst) {
		this(perSecond, burst, System::nanoTime);
	}

	/** Milliseconds until a call would be allowed: 0 when it is allowed now (and a token was taken). */
	public synchronized long tryAcquire() {
		refill();
		if (tokens >= 1) {
			tokens -= 1;
			return 0;
		}
		return (long) Math.ceil((1 - tokens) / perSecond * 1000.0);
	}

	/** Give back a token taken by {@link #tryAcquire} when a later check rejected the call anyway. */
	public synchronized void refund() {
		tokens = Math.min(burst, tokens + 1);
	}

	private void refill() {
		long now = nanoClock.getAsLong();
		tokens = Math.min(burst, tokens + (now - last) / 1e9 * perSecond);
		last = now;
	}
}
