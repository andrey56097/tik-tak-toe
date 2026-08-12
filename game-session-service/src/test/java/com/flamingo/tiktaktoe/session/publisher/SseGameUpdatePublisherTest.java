package com.flamingo.tiktaktoe.session.publisher;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.session.domain.MoveHistoryEntry;
import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SseGameUpdatePublisher}.
 *
 * <p>Uses a spy with {@link #createEmitter()} returning a {@link TestEmitter}
 * — a real {@link SseEmitter} subclass that overrides {@code send()} and
 * {@code complete()} to record every call, so a test can assert what was sent
 * rather than only that something was. (An emitter Spring has not yet
 * initialized buffers its sends instead of performing I/O, so the override is
 * about observability, not about making the emitter usable here.) The other
 * subclasses each fail in one specific way to drive a single error branch.
 */
class SseGameUpdatePublisherTest {

    private final SseGameUpdatePublisher publisher = spy(new SseGameUpdatePublisher(120_000L));

    /**
     * A real {@link SseEmitter} that captures every {@code send()} call
     * instead of delegating to the servlet container. Also tracks
     * completion and registered callbacks so the test can drive eviction.
     */
    static class TestEmitter extends SseEmitter {
        final List<SseEventBuilder> sends = new CopyOnWriteArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<Throwable> completedWithError = new AtomicReference<>();
        final List<Runnable> onCompletionCallbacks = new ArrayList<>();
        final List<Runnable> onTimeoutCallbacks = new ArrayList<>();
        final List<java.util.function.Consumer<Throwable>> onErrorCallbacks = new ArrayList<>();

        TestEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sends.add(builder);
        }

        @Override
        public void complete() {
            completed.set(true);
            for (Runnable cb : onCompletionCallbacks) {
                cb.run();
            }
        }

        @Override
        public void completeWithError(Throwable ex) {
            completedWithError.set(ex);
        }

        @Override
        public void onCompletion(Runnable callback) {
            onCompletionCallbacks.add(callback);
        }

        @Override
        public void onTimeout(Runnable callback) {
            onTimeoutCallbacks.add(callback);
        }

        @Override
        public void onError(java.util.function.Consumer<Throwable> callback) {
            onErrorCallbacks.add(callback);
        }
    }

    /** Like {@link TestEmitter} but throws on the very first {@code send()}. */
    static final class FailingEmitter extends TestEmitter {
        private boolean firstSend = true;

        FailingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (firstSend) {
                firstSend = false;
                throw new IOException("simulated disconnect");
            }
            super.send(builder);
        }
    }

    /**
     * Survives subscribe (first send succeeds) but throws on the second
     * send — the one {@code publish} calls — to cover the
     * {@code catch (IOException)} path in {@code publish}.
     */
    static final class PublishFailingEmitter extends TestEmitter {
        private int sendCount = 0;

        PublishFailingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendCount++;
            if (sendCount == 2) {
                throw new IOException("simulated disconnect during publish");
            }
            super.send(builder);
        }
    }

    /**
     * Survives subscribe but throws a <em>runtime</em> exception — not an
     * {@link IOException} — on the send that {@code publish} makes. This is
     * what {@link SseEmitter#send} actually does when the container has already
     * completed the emitter ({@code IllegalStateException}), or when the
     * payload cannot be serialized. A publisher that only catches
     * {@link IOException} lets it escape the subscriber loop.
     */
    static final class PublishThrowingRuntimeEmitter extends TestEmitter {
        int sendAttempts = 0;

        PublishThrowingRuntimeEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendAttempts++;
            if (sendAttempts >= 2) {
                throw new IllegalStateException("ResponseBodyEmitter has already completed");
            }
            super.send(builder);
        }
    }

    /**
     * Fails the same way as {@link PublishThrowingRuntimeEmitter}, and then
     * fails again from {@code completeWithError}. The real emitter can do this
     * only in one remote case — the servlet container ending the async cycle
     * between Spring's own completion check and its dispatch — but it is the
     * one call in the error handler that reaches outside this class, so the
     * handler must be ordered to survive it.
     */
    static final class CompleteWithErrorThrowingEmitter extends TestEmitter {
        int sendAttempts = 0;

        CompleteWithErrorThrowingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendAttempts++;
            if (sendAttempts >= 2) {
                throw new IllegalStateException("ResponseBodyEmitter has already completed");
            }
            super.send(builder);
        }

        @Override
        public void completeWithError(Throwable ex) {
            super.completeWithError(ex);
            throw new IllegalStateException("async cycle already ended");
        }
    }

    /** How long {@link PausingOnHashEmitter} holds the subscribe/evict window open. */
    private static final long PAUSE_MS = 300L;

    /**
     * Pauses once inside {@code hashCode()}, which {@link
     * java.util.concurrent.ConcurrentHashMap} invokes at the very start of
     * {@code add()} — before it touches the table. That places the pause
     * exactly inside the subscribe/evict window: the registry lookup for the
     * session has already happened, but this emitter is not in the set yet.
     */
    static final class PausingOnHashEmitter extends TestEmitter {
        final CountDownLatch reachedHashCode = new CountDownLatch(1);
        final AtomicBoolean armed = new AtomicBoolean(false);

        PausingOnHashEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public int hashCode() {
            if (armed.compareAndSet(true, false)) {
                reachedHashCode.countDown();
                try {
                    Thread.sleep(PAUSE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.hashCode();
        }
    }

    /**
     * Hands out pre-built emitters without Mockito — the concurrency test
     * calls the publisher from two threads at once, and a spy's invocation
     * bookkeeping is not built for that.
     */
    static final class QueuedEmitterPublisher extends SseGameUpdatePublisher {
        private final Queue<SseEmitter> queue;

        QueuedEmitterPublisher(long timeoutMs, SseEmitter... emitters) {
            super(timeoutMs);
            this.queue = new ConcurrentLinkedQueue<>(List.of(emitters));
        }

        @Override
        SseEmitter createEmitter() {
            return queue.poll();
        }
    }

    private static SessionRecord session(String id, SessionStatus status, int moveCount) {
        List<MoveHistoryEntry> moves = new ArrayList<>();
        for (int i = 0; i < moveCount; i++) {
            moves.add(new MoveHistoryEntry(CellState.X, 0, i));
        }
        List<List<CellState>> board = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            List<CellState> row = new ArrayList<>();
            for (int c = 0; c < 3; c++) {
                row.add(CellState.EMPTY);
            }
            board.add(row);
        }
        GameState state = new GameState(id, board, GameStatus.IN_PROGRESS, CellState.X, null);
        return new SessionRecord(id, status, state, moves);
    }

    // ---- publish with no subscribers ----

    @Test
    void publish_withNoSubscribers_isANoOp() {
        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1));
    }

    @Test
    void publish_withTerminalStatusAndNoSubscribers_isANoOp() {
        publisher.publish("s1", session("s1", SessionStatus.COMPLETED, 0));
    }

    @Test
    void publish_withFailedStatusAndNoSubscribers_isANoOp() {
        publisher.publish("s1", session("s1", SessionStatus.FAILED, 1));
    }

    // ---- publish with subscribers ----

    @Test
    void publish_withSubscribers_sendsEvent() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));
        int afterSubscribe = em.sends.size();

        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1));

        assertThat(em.sends).hasSize(afterSubscribe + 1);
    }

    @Test
    void publish_withTerminalStatus_sendsDoneAndCompletes() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));

        publisher.publish("s1", session("s1", SessionStatus.COMPLETED, 1));

        assertThat(em.completed).isTrue();
    }

    @Test
    void publish_withFailedStatus_sendsDoneAndCompletes() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));

        publisher.publish("s1", session("s1", SessionStatus.FAILED, 1));

        assertThat(em.completed).isTrue();
    }

    @Test
    void publish_whenSendThrows_evictsAndContinues() {
        PublishFailingEmitter em = new PublishFailingEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));

        // publish — the emitter throws on its second send(), which is the publish one.
        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1));

        // The emitter was evicted, so a second publish is a no-op.
        int sendsBeforeSecondPublish = em.sends.size();
        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 2));
        assertThat(em.sends).hasSize(sendsBeforeSecondPublish);
    }

    // ---- publish when send fails with something other than IOException ----

    @Test
    void publish_whenSendThrowsRuntimeException_doesNotPropagate() {
        PublishThrowingRuntimeEmitter em = new PublishThrowingRuntimeEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));

        assertThatCode(() -> publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_whenSendThrowsRuntimeException_evictsSubscriber() {
        PublishThrowingRuntimeEmitter em = new PublishThrowingRuntimeEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));
        catchThrowable(() -> publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1)));

        int attemptsAfterFirstPublish = em.sendAttempts;
        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 2));

        assertThat(em.sendAttempts).isEqualTo(attemptsAfterFirstPublish);
    }

    @Test
    void publish_whenSendThrowsRuntimeException_stillAttemptsEverySubscriber() {
        PublishThrowingRuntimeEmitter e1 = new PublishThrowingRuntimeEmitter(120_000L);
        PublishThrowingRuntimeEmitter e2 = new PublishThrowingRuntimeEmitter(120_000L);
        doReturn(e1, e2).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));

        catchThrowable(() -> publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1)));

        // Both emitters must have been reached: whichever one the set iterates
        // first, its failure must not abort delivery to the rest.
        assertThat(e1.sendAttempts).isEqualTo(2);
        assertThat(e2.sendAttempts).isEqualTo(2);
    }

    @Test
    void publish_whenSendThrowsRuntimeException_completesTheEmitterWithThatError() {
        PublishThrowingRuntimeEmitter em = new PublishThrowingRuntimeEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));

        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1));

        // Nothing else will ever close this response: unlike the IOException
        // case, the container gets no notification, so the browser would hang
        // on an open stream until the emitter's own timeout.
        // Pinned to the message the emitter actually threw: asserting the type
        // alone would also pass on a fresh exception invented by the handler.
        assertThat(em.completedWithError.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ResponseBodyEmitter has already completed");
    }

    @Test
    void publish_whenSendThrowsIOException_leavesCompletionToTheContainer() {
        PublishFailingEmitter em = new PublishFailingEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));

        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1));

        // Per SseEmitter's contract the servlet container raises the completion
        // notification itself for a failed write, so completing again here would
        // be redundant.
        assertThat(em.completedWithError.get()).isNull();
    }

    @Test
    void publish_whenCompletingWithErrorItselfFails_theSubscriberIsStillEvicted() {
        CompleteWithErrorThrowingEmitter em = new CompleteWithErrorThrowingEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));
        catchThrowable(() -> publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1)));

        // Registry state must not depend on an outward call succeeding: a
        // subscriber left registered here would be retried, and re-logged, on
        // every publish for the rest of the session.
        int attemptsAfterFirstPublish = em.sendAttempts;
        catchThrowable(() -> publisher.publish("s1", session("s1", SessionStatus.RUNNING, 2)));

        assertThat(em.sendAttempts).isEqualTo(attemptsAfterFirstPublish);
    }

    // ---- subscribe racing with eviction ----

    @Test
    void subscribe_racingWithEvictionOfTheLastSubscriber_doesNotOrphanTheNewSubscriber() throws Exception {
        TestEmitter existing = new TestEmitter(120_000L);
        PausingOnHashEmitter joining = new PausingOnHashEmitter(120_000L);
        SseGameUpdatePublisher racing = new QueuedEmitterPublisher(120_000L, existing, joining);
        racing.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));

        joining.armed.set(true);
        Thread subscriber = new Thread(
                () -> racing.subscribe("s1", session("s1", SessionStatus.RUNNING, 0)), "joining-subscriber");
        subscriber.start();

        // Evict the only other subscriber while the joining one is mid-registration:
        // the session's set goes empty, and an unguarded registry drops the whole entry.
        assertThat(joining.reachedHashCode.await(5, TimeUnit.SECONDS)).isTrue();
        racing.evict("s1", existing);
        subscriber.join(10_000);

        int beforePublish = joining.sends.size();
        racing.publish("s1", session("s1", SessionStatus.RUNNING, 1));

        assertThat(joining.sends).hasSize(beforePublish + 1);
    }

    // ---- subscribe ----

    @Test
    void subscribe_returnsTheCreatedEmitter() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();

        SseEmitter result = publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));

        assertThat(result).isSameAs(em);
    }

    @Test
    void subscribe_registersCallbacks() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();

        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));

        assertThat(em.onCompletionCallbacks).hasSize(1);
        assertThat(em.onTimeoutCallbacks).hasSize(1);
        assertThat(em.onErrorCallbacks).hasSize(1);
    }

    @Test
    void subscribe_withEmptyMoveHistory_sendsOneEvent() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();

        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));

        assertThat(em.sends).hasSize(1);
    }

    @Test
    void subscribe_withNonEmptyMoveHistory_sendsOneEvent() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();

        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 3));

        assertThat(em.sends).hasSize(1);
    }

    @Test
    void subscribe_whenTerminal_sendsTwoEvents_andCompletes() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();

        publisher.subscribe("s1", session("s1", SessionStatus.COMPLETED, 0));

        // Two sends: initial state + "done" event.
        assertThat(em.sends).hasSize(2);
        assertThat(em.completed).isTrue();
    }

    @Test
    void subscribe_whenFailed_sendsTwoEvents_andCompletes() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();

        publisher.subscribe("s1", session("s1", SessionStatus.FAILED, 3));

        assertThat(em.sends).hasSize(2);
        assertThat(em.completed).isTrue();
    }

    @Test
    void subscribe_whenInitialSendThrows_evictsAndStillReturnsEmitter() {
        FailingEmitter em = new FailingEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();

        SseEmitter result = publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));
        int sendsAfterFailedSubscribe = em.sends.size();

        // The emitter is returned even though the send failed — the controller sees it.
        assertThat(result).isSameAs(em);
        // After eviction, publishing must not reach the emitter.
        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1));
        assertThat(em.sends).hasSize(sendsAfterFailedSubscribe);
    }

    // ---- eviction ----

    @Test
    void evict_removesTheEmitter_soPublishIsNoOp() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));
        int beforePublish = em.sends.size();
        publisher.evict("s1", em);

        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1));

        assertThat(em.sends).hasSize(beforePublish);
    }

    @Test
    void evict_whenOneOfTwoEmittersRemoved_stillPublishesToOther() {
        TestEmitter e1 = new TestEmitter(120_000L);
        TestEmitter e2 = new TestEmitter(120_000L);
        doReturn(e1, e2).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));

        int e2BeforePublish = e2.sends.size();
        publisher.evict("s1", e1);

        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1));

        assertThat(e2.sends).hasSize(e2BeforePublish + 1);
    }

    @Test
    void evict_twiceOnSameEmitter_isIdempotent() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));
        publisher.evict("s1", em);
        publisher.evict("s1", em);
    }

    @Test
    void evict_onUnknownSession_doesNotThrow() {
        publisher.evict("nonexistent", new TestEmitter(120_000L));
    }

    @Test
    void evict_onUnknownSession_whenEmittersIsNull_doesNotThrow() {
        new SseGameUpdatePublisher(60_000L).evict("nonexistent", new TestEmitter(60_000L));
    }

    // ---- callback-driven eviction ----

    @Test
    void emitCompletion_triggersEviction() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));
        int beforeEviction = em.sends.size();

        em.onCompletionCallbacks.get(0).run();

        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1));
        assertThat(em.sends).hasSize(beforeEviction);
    }

    @Test
    void emitTimeout_triggersEviction() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));
        int beforeEviction = em.sends.size();

        em.onTimeoutCallbacks.get(0).run();

        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1));
        assertThat(em.sends).hasSize(beforeEviction);
    }

    @Test
    void emitError_triggersEviction() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));
        int beforeEviction = em.sends.size();

        em.onErrorCallbacks.get(0).accept(new RuntimeException("client gone"));

        publisher.publish("s1", session("s1", SessionStatus.RUNNING, 1));
        assertThat(em.sends).hasSize(beforeEviction);
    }

    // ---- constructor ----

    /**
     * Asserts the configured timeout on a <em>real</em> emitter. Stubbing
     * {@code createEmitter()} here would assert nothing: the wiring under test
     * is precisely the one the stub replaces, so a hardcoded timeout in
     * {@code createEmitter()} would still pass.
     */
    @Test
    void constructorTimeoutReachesTheCreatedEmitter() {
        SseGameUpdatePublisher custom = new SseGameUpdatePublisher(30_000L);

        assertThat(custom.createEmitter().getTimeout()).isEqualTo(30_000L);
    }

    @Test
    void aDifferentConstructorTimeoutReachesTheCreatedEmitter() {
        SseGameUpdatePublisher custom = new SseGameUpdatePublisher(45_000L);

        assertThat(custom.createEmitter().getTimeout()).isEqualTo(45_000L);
    }

    // ---- event id computation ----

    @Test
    void eventId_withEmptyHistory_returnsZero() {
        assertThat(SseGameUpdatePublisher.eventId(session("s1", SessionStatus.RUNNING, 0)))
                .isEqualTo("0");
    }

    @Test
    void eventId_withNonEmptyHistory_returnsMoveCount() {
        assertThat(SseGameUpdatePublisher.eventId(session("s1", SessionStatus.RUNNING, 3)))
                .isEqualTo("3");
    }

    @Test
    void eventId_withOneMove_returnsOne() {
        assertThat(SseGameUpdatePublisher.eventId(session("s1", SessionStatus.RUNNING, 1)))
                .isEqualTo("1");
    }

    // ---- multiple sessions ----

    @Test
    void publish_toDifferentSession_doesNotAffectSubscriber() {
        TestEmitter em = new TestEmitter(120_000L);
        doReturn(em).when(publisher).createEmitter();
        publisher.subscribe("s1", session("s1", SessionStatus.RUNNING, 0));
        int beforePublish = em.sends.size();

        publisher.publish("s2", session("s2", SessionStatus.RUNNING, 1));
        assertThat(em.sends).hasSize(beforePublish);
    }
}
