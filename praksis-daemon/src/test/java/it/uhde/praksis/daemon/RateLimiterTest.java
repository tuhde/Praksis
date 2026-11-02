// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
  private static final class Now extends Clock {
    Instant now = Instant.parse("2026-11-02T10:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void allowsUpToTheLimitPerKeyAndRecoversAfterTheWindow() {
    Now clock = new Now();
    var l = new RateLimiter(2, Duration.ofSeconds(10), clock);
    assertTrue(l.allow("a"));
    assertTrue(l.allow("a"));
    assertFalse(l.allow("a"));
    assertTrue(l.blocked("a"));
    assertTrue(l.allow("b"));
    clock.now = clock.now.plusSeconds(11);
    assertFalse(l.blocked("a"));
    assertTrue(l.allow("a"));
  }
}
