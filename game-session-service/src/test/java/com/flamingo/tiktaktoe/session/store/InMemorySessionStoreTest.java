package com.flamingo.tiktaktoe.session.store;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.exception.SessionCapacityException;
import com.flamingo.tiktaktoe.session.exception.SessionConflictException;
import com.flamingo.tiktaktoe.session.exception.SessionNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link InMemorySessionStore}: save/find round-trip, the
 * {@code CREATED}-only claim guard ({@code 404}/{@code 409} paths), and the
 * atomicity of {@code claimForRunning} under concurrency (two racing callers,
 * exactly one wins).
 */
class InMemorySessionStoreTest {

    /** Wide enough that retention and the ceiling never interfere with the tests above them. */
    private static final SessionRetentionPolicy UNCONSTRAINED =
            new SessionRetentionPolicy(Duration.ofDays(1), 1000);

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemorySessionStore store =
            new InMemorySessionStore(UNCONSTRAINED, Clock.fixed(NOW, ZoneOffset.UTC));

    private static InMemorySessionStore storeWith(SessionRetentionPolicy policy) {
        return new InMemorySessionStore(policy, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SessionRecord created(String sessionId) {
        return new SessionRecord(sessionId, SessionStatus.CREATED, null, List.of());
    }

    @Test
    void saveAndFind_roundTripsTheRecord() {
        SessionRecord record = new SessionRecord("s1", SessionStatus.RUNNING,
                boardState("s1", GameStatus.IN_PROGRESS), List.of());

        store.save(record);

        assertThat(store.find("s1")).isEqualTo(record);
    }

    @Test
    void find_withUnknownId_returnsNull() {
        assertThat(store.find("does-not-exist")).isNull();
    }

    @Test
    void save_overwritesAnExistingRecordForTheSameId() {
        store.save(created("s1"));
        SessionRecord updated = new SessionRecord("s1", SessionStatus.COMPLETED, null, List.of());

        store.save(updated);

        assertThat(store.find("s1")).isEqualTo(updated);
    }

    @Test
    void save_returnsTheExactRecordItWasGiven() {
        SessionRecord record = created("s1");

        assertThat(store.save(record)).isSameAs(record);
    }

    @Test
    void save_overwrite_returnsTheNewRecord() {
        store.save(created("s1"));
        SessionRecord updated = new SessionRecord("s1", SessionStatus.COMPLETED, null, List.of());

        assertThat(store.save(updated)).isSameAs(updated);
    }

    @Test
    void claimForRunning_withUnknownId_throwsSessionNotFoundException() {
        assertThatThrownBy(() -> store.claimForRunning("does-not-exist"))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void claimForRunning_onAlreadyRunningSession_throwsSessionConflictException() {
        store.save(new SessionRecord("s1", SessionStatus.RUNNING, null, List.of()));

        assertThatThrownBy(() -> store.claimForRunning("s1"))
                .isInstanceOf(SessionConflictException.class);
    }

    @Test
    void claimForRunning_onCompletedOrFailedSession_throwsSessionConflictException() {
        store.save(new SessionRecord("s1", SessionStatus.COMPLETED, null, List.of()));
        assertThatThrownBy(() -> store.claimForRunning("s1")).isInstanceOf(SessionConflictException.class);

        store.save(new SessionRecord("s2", SessionStatus.FAILED, null, List.of()));
        assertThatThrownBy(() -> store.claimForRunning("s2")).isInstanceOf(SessionConflictException.class);
    }

    @Test
    void claimForRunning_onCreatedSession_returnsRunningRecord_andStoreReflectsIt() {
        store.save(created("s1"));

        SessionRecord running = store.claimForRunning("s1");

        assertThat(running.sessionId()).isEqualTo("s1");
        assertThat(running.status()).isEqualTo(SessionStatus.RUNNING);
        assertThat(store.find("s1").status()).isEqualTo(SessionStatus.RUNNING);
    }

    @Test
    void claimForRunning_concurrentDoubleClaim_exactlyOneSucceeds() throws Exception {
        store.save(created("s1"));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    try {
                        SessionRecord claimed = store.claimForRunning("s1");
                        if (claimed.status() == SessionStatus.RUNNING) {
                            succeeded.incrementAndGet();
                        }
                    } catch (SessionConflictException e) {
                        conflicted.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
                    .as("claim threads should finish promptly")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).as("exactly one thread must win the claim").isEqualTo(1);
        assertThat(conflicted.get()).as("the loser must be rejected with a conflict").isEqualTo(1);
        assertThat(store.find("s1").status()).isEqualTo(SessionStatus.RUNNING);
    }

    // --- Retention -------------------------------------------------------------
    //
    // Time is injected, never slept: a test that waits out a real TTL is slow and
    // flaky, and one that passes a later Instant is neither. evictExpired returns
    // how many it removed so the sweep is observable without reaching into the map.

    @Test
    void aTerminalSessionIsEvictedOnceItIsOlderThanTheRetention() {
        InMemorySessionStore store = storeWith(new SessionRetentionPolicy(Duration.ofMinutes(10), 100));
        store.save(new SessionRecord("done", SessionStatus.COMPLETED, null, List.of()));

        assertThat(store.evictExpired(NOW.plus(Duration.ofMinutes(9)))).isZero();
        assertThat(store.find("done")).as("still inside the retention window").isNotNull();

        assertThat(store.evictExpired(NOW.plus(Duration.ofMinutes(11)))).isEqualTo(1);
        assertThat(store.find("done")).as("past the retention window").isNull();
    }

    @Test
    void aFailedSessionIsEvictedTooItIsJustAsTerminalAsACompletedOne() {
        InMemorySessionStore store = storeWith(new SessionRetentionPolicy(Duration.ofMinutes(10), 100));
        store.save(new SessionRecord("dead", SessionStatus.FAILED, null, List.of()));

        assertThat(store.evictExpired(NOW.plus(Duration.ofMinutes(11)))).isEqualTo(1);
        assertThat(store.find("dead")).isNull();
    }

    /**
     * A simulation in flight must never lose its record, however long it runs —
     * evicting it would strand the run with nowhere to write its result.
     */
    @Test
    void aRunningSessionIsNeverEvictedNoMatterHowOld() {
        InMemorySessionStore store = storeWith(new SessionRetentionPolicy(Duration.ofMinutes(10), 100));
        store.save(new SessionRecord("busy", SessionStatus.RUNNING, null, List.of()));

        assertThat(store.evictExpired(NOW.plus(Duration.ofDays(365)))).isZero();
        assertThat(store.find("busy")).isNotNull();
    }

    /**
     * CREATED is not terminal either: a session may sit un-simulated for a while
     * between {@code POST /sessions} and {@code POST /simulate}.
     */
    @Test
    void aCreatedButUnstartedSessionIsNotEvicted() {
        InMemorySessionStore store = storeWith(new SessionRetentionPolicy(Duration.ofMinutes(10), 100));
        store.save(created("waiting"));

        assertThat(store.evictExpired(NOW.plus(Duration.ofDays(365)))).isZero();
        assertThat(store.find("waiting")).isNotNull();
    }

    /** Retention runs from the last write, not from creation. */
    @Test
    void theRetentionClockRestartsOnEveryUpdate() {
        InMemorySessionStore store = new InMemorySessionStore(
                new SessionRetentionPolicy(Duration.ofMinutes(10), 100),
                Clock.fixed(NOW.plus(Duration.ofMinutes(8)), ZoneOffset.UTC));

        store.save(new SessionRecord("s", SessionStatus.COMPLETED, null, List.of()));

        // 9 minutes after NOW is only 1 minute after the write above.
        assertThat(store.evictExpired(NOW.plus(Duration.ofMinutes(9)))).isZero();
        assertThat(store.evictExpired(NOW.plus(Duration.ofMinutes(19)))).isEqualTo(1);
    }

    // --- Capacity --------------------------------------------------------------

    @Test
    void creatingBeyondTheCeilingIsRejectedRatherThanGrowingForever() {
        InMemorySessionStore store = storeWith(new SessionRetentionPolicy(Duration.ofMinutes(10), 2));
        store.save(created("a"));
        store.save(created("b"));

        assertThatThrownBy(() -> store.save(created("c")))
                .isInstanceOf(SessionCapacityException.class);
        assertThat(store.find("c")).isNull();
    }

    /**
     * The ceiling rejects <em>new</em> sessions only. Rejecting an update would
     * strand a simulation that is already running — it could never record its
     * result, and would be far worse than refusing to start another.
     */
    @Test
    void updatingAnExistingSessionIsNeverRejectedByTheCeiling() {
        InMemorySessionStore store = storeWith(new SessionRetentionPolicy(Duration.ofMinutes(10), 1));
        store.save(created("a"));

        assertThatCode(() -> store.save(new SessionRecord("a", SessionStatus.RUNNING, null, List.of())))
                .doesNotThrowAnyException();
        assertThat(store.find("a").status()).isEqualTo(SessionStatus.RUNNING);
    }

    @Test
    void evictingASessionFreesItsSlotUnderTheCeiling() {
        InMemorySessionStore store = storeWith(new SessionRetentionPolicy(Duration.ofMinutes(10), 1));
        store.save(new SessionRecord("old", SessionStatus.COMPLETED, null, List.of()));

        assertThatThrownBy(() -> store.save(created("new")))
                .isInstanceOf(SessionCapacityException.class);

        store.evictExpired(NOW.plus(Duration.ofMinutes(11)));

        assertThatCode(() -> store.save(created("new"))).doesNotThrowAnyException();
    }

    /**
     * The ceiling must hold under concurrent creates with distinct ids — a
     * check-then-act on {@code Map.size()} inside per-key {@code compute} is
     * not enough; many threads can all observe {@code size < max} and all insert.
     *
     * <p>Several hammer rounds raise the chance of exposing a race on a buggy
     * implementation; any single overrun fails the test.
     */
    @Test
    void concurrentCreatesWithDistinctIds_neverExceedTheCeiling() throws Exception {
        int maxSessions = 5;
        int attempts = 80;
        int rounds = 25;
        int worstPresent = 0;
        int worstSaved = 0;

        for (int round = 0; round < rounds; round++) {
            InMemorySessionStore store = storeWith(new SessionRetentionPolicy(Duration.ofMinutes(10), maxSessions));

            ExecutorService pool = Executors.newFixedThreadPool(attempts);
            CountDownLatch ready = new CountDownLatch(attempts);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger saved = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            try {
                for (int i = 0; i < attempts; i++) {
                    final String id = "r" + round + "-concurrent-" + i;
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        try {
                            store.save(created(id));
                            saved.incrementAndGet();
                        } catch (SessionCapacityException e) {
                            rejected.incrementAndGet();
                        }
                    });
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).as("all workers ready round %d", round).isTrue();
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                        .as("concurrent save workers should finish round %d", round)
                        .isTrue();
            } finally {
                pool.shutdownNow();
            }

            int present = 0;
            for (int i = 0; i < attempts; i++) {
                if (store.find("r" + round + "-concurrent-" + i) != null) {
                    present++;
                }
            }

            assertThat(saved.get() + rejected.get())
                    .as("every attempt succeeds or throws SessionCapacityException (round %d)", round)
                    .isEqualTo(attempts);
            assertThat(rejected.get())
                    .as("overflow attempts must throw SessionCapacityException (round %d)", round)
                    .isEqualTo(attempts - saved.get());
            assertThat(present).isEqualTo(saved.get());

            worstPresent = Math.max(worstPresent, present);
            worstSaved = Math.max(worstSaved, saved.get());
        }

        assertThat(worstPresent)
                .as("across %d rounds, stored distinct sessions must never overrun maxSessions=%d (worst=%d)",
                        rounds, maxSessions, worstPresent)
                .isLessThanOrEqualTo(maxSessions);
        assertThat(worstSaved)
                .as("across %d rounds, successful saves must never overrun maxSessions=%d (worst=%d)",
                        rounds, maxSessions, worstSaved)
                .isLessThanOrEqualTo(maxSessions);
    }

    private static GameState boardState(String gameId, GameStatus status) {
        return new GameState(gameId,
                List.of(
                        List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                        List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                        List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY)),
                status, CellState.X, null);
    }
}
