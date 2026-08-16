package com.interviewprep.node.service.llm;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * A minimum-interval gate per host, so several concurrent enrichment jobs each running several
 * queries don't hammer the same search provider or the same target site into a rate limit.
 * Deterministic, no LLM — a plain scheduling utility, not a token-bucket library: this project's
 * scale doesn't need one.
 *
 * <p>{@link #acquire} blocks the calling thread with a short {@code Thread.sleep}, so it must only
 * be called from a worker/executor thread (e.g. inside a {@link
 * com.interviewprep.node.service.llm.enrichment.ResourceFinder} tool call), never from a thread
 * orchestrating other work.
 */
@Component
public class HostRateLimiter {

  private static final long MIN_INTERVAL_MS = 350;

  private final ConcurrentHashMap<String, AtomicLong> nextAllowedAt = new ConcurrentHashMap<>();

  public void acquire(String host) {
    AtomicLong slot = nextAllowedAt.computeIfAbsent(host, h -> new AtomicLong(0));
    long waitUntil =
        slot.updateAndGet(prev -> Math.max(prev, System.currentTimeMillis()) + MIN_INTERVAL_MS);
    long sleepMs = waitUntil - MIN_INTERVAL_MS - System.currentTimeMillis();
    if (sleepMs <= 0) {
      return;
    }
    try {
      Thread.sleep(sleepMs);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
