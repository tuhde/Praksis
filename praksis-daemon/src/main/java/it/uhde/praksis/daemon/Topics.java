// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.protocol.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Which parts of the state changed and when, so that a client can keep its screens up to date by
 * asking "what changed since" and waiting for the answer. The counter only goes up; a client sends
 * the counter of its last answer.
 */
final class Topics {
  /** Every topic, in the order a client should refresh them. */
  static final List<String> ALL = List.of("events", "log", "approvals", "shadow", "audit");

  /** The longest a request waits for a change. */
  static final long MAX_WAIT_MILLIS = 30_000;

  private long counter = 1;
  private final Map<String, Long> last = new HashMap<>();
  private boolean closed;

  /** Notes that something changed. Quick: it is called while the store is locked. */
  synchronized void bump(String topic) {
    counter++;
    last.put(topic, counter);
    notifyAll();
  }

  /** Lets everyone who waits go, at shutdown. */
  synchronized void close() {
    closed = true;
    notifyAll();
  }

  /**
   * The topics that changed after {@code since}, waiting up to {@code waitMillis} if there are
   * none. With 0, every topic is returned at once.
   */
  synchronized Response.ChangeList changes(long since, long waitMillis)
      throws InterruptedException {
    if (since <= 0) {
      return new Response.ChangeList(counter, ALL);
    }
    long deadline =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(waitMillis, MAX_WAIT_MILLIS));
    List<String> changed = changedSince(since);
    while (changed.isEmpty() && !closed) {
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        break;
      }
      TimeUnit.NANOSECONDS.timedWait(this, left);
      changed = changedSince(since);
    }
    return new Response.ChangeList(counter, changed);
  }

  private List<String> changedSince(long since) {
    List<String> out = new ArrayList<>();
    for (String topic : ALL) {
      if (last.getOrDefault(topic, 0L) > since) {
        out.add(topic);
      }
    }
    return out;
  }
}
