// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Allows at most a number of events per key in a sliding window. */
final class RateLimiter {
  private final int max;
  private final Duration window;
  private final Clock clock;
  private final Map<String, ArrayDeque<Long>> seen = new HashMap<>();

  RateLimiter(int max, Duration window, Clock clock) {
    this.max = max;
    this.window = window;
    this.clock = clock;
  }

  /** Counts an event for the key; false if it is over the limit (and then it is not counted). */
  synchronized boolean allow(String key) {
    long now = clock.millis();
    ArrayDeque<Long> q = seen.computeIfAbsent(key, k -> new ArrayDeque<>());
    while (!q.isEmpty() && now - q.peekFirst() >= window.toMillis()) {
      q.pollFirst();
    }
    if (q.size() >= max) {
      return false;
    }
    q.addLast(now);
    return true;
  }

  /** Whether the key is over the limit, without counting. */
  synchronized boolean blocked(String key) {
    long now = clock.millis();
    ArrayDeque<Long> q = seen.get(key);
    if (q == null) {
      return false;
    }
    while (!q.isEmpty() && now - q.peekFirst() >= window.toMillis()) {
      q.pollFirst();
    }
    if (q.isEmpty()) {
      seen.remove(key);
    }
    return q.size() >= max;
  }
}
