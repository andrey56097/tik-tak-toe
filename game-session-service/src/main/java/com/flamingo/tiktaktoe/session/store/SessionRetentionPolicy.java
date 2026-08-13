package com.flamingo.tiktaktoe.session.store;

import java.time.Duration;

/**
 * How long finished sessions are kept, and how many sessions may exist at once.
 *
 * <p>Both numbers exist for the same reason: an in-memory store that only ever
 * grows is a memory leak with a public endpoint in front of it. Retention bounds
 * it over time, the ceiling bounds it under a burst.
 *
 * @param terminalRetention how long a {@code COMPLETED}/{@code FAILED} session is
 *                          kept after its last update — long enough for a browser
 *                          to fetch the result and an operator to look at it
 * @param maxSessions       the hard ceiling on stored sessions. A ceiling, not a
 *                          target: reaching it means sessions are being created
 *                          faster than they finish, and refusing one is better
 *                          than running out of heap
 */
public record SessionRetentionPolicy(Duration terminalRetention, int maxSessions) {

    public SessionRetentionPolicy {
        if (terminalRetention == null || terminalRetention.isNegative()) {
            throw new IllegalArgumentException("terminalRetention must be a non-negative duration");
        }
        if (maxSessions < 1) {
            throw new IllegalArgumentException("maxSessions must be at least 1");
        }
    }
}
