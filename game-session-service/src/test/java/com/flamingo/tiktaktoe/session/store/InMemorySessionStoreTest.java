package com.flamingo.tiktaktoe.session.store;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.exception.SessionConflictException;
import com.flamingo.tiktaktoe.session.exception.SessionNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link InMemorySessionStore}: save/find round-trip, the
 * {@code CREATED}-only claim guard ({@code 404}/{@code 409} paths), and the
 * atomicity of {@code claimForRunning} under concurrency (two racing callers,
 * exactly one wins).
 */
class InMemorySessionStoreTest {

    private final InMemorySessionStore store = new InMemorySessionStore();

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

    private static GameState boardState(String gameId, GameStatus status) {
        return new GameState(gameId,
                List.of(
                        List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                        List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY),
                        List.of(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY)),
                status, CellState.X, null);
    }
}
