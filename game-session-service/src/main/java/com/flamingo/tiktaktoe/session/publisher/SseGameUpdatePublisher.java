package com.flamingo.tiktaktoe.session.publisher;

import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.dto.SessionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE adapter implementing {@link GameUpdatePublisher}. Holds a registry of
 * {@link SseEmitter}s keyed by session id; publishes the full {@link
 * SessionResponse} after every state transition, and signals game end with a
 * named {@code done} event so the browser can close the connection rather than
 * reconnecting in a loop.
 *
 * <p>Subscribers are evicted on completion, timeout, error and client
 * disconnect — the registry cannot grow unboundedly.
 */
@Component
public class SseGameUpdatePublisher implements GameUpdatePublisher {

    private static final Logger log = LoggerFactory.getLogger(SseGameUpdatePublisher.class);

    private final ConcurrentHashMap<String, Set<SseEmitter>> registry = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public SseGameUpdatePublisher(
            @Value("${session.stream.timeout-ms:120000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void publish(String sessionId, SessionRecord record) {
        Set<SseEmitter> emitters = registry.get(sessionId);
        if (emitters == null) {
            return; // no subscribers — no-op, not a failure
        }

        SessionResponse response = SessionResponse.from(record);
        boolean isTerminal = record.status() == SessionStatus.COMPLETED
                || record.status() == SessionStatus.FAILED;

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(record.moveHistory().size()))
                        .data(response));
                if (isTerminal) {
                    emitter.send(SseEmitter.event().name("done").data(""));
                    emitter.complete();
                }
            } catch (IOException e) {
                // Writing to a dead connection — client disconnected or the network
                // dropped. Routine client behavior, so it is logged at DEBUG:
                // traceable when diagnosing, silent in normal operation. Evict now
                // rather than waiting on the container's onCompletion callback, so
                // later publishes do not keep writing to the dead emitter.
                log.debug("Client disconnected from session {}, evicting subscriber", sessionId, e);
                evict(sessionId, emitter);
            } catch (Exception e) {
                // send() also throws unchecked: IllegalStateException once the
                // container has completed the emitter (a timeout racing this loop),
                // or a serialization failure. Caught for the same reason as the
                // checked case, and separately because one broken subscriber must
                // never abort the loop — the remaining subscribers would silently
                // miss this update. Logged at WARN: unlike a disconnect, this is
                // not routine.
                //
                // Unlike the IOException case the container raises no completion
                // notification here, so the response stays open until the emitter
                // times out — minutes of a browser waiting on a stream that will
                // never produce another event. completeWithError closes it now,
                // and the completion callback it eventually fires re-enters evict
                // harmlessly, since evict is idempotent.
                //
                // Eviction comes first on purpose. completeWithError is the only
                // call in this handler that reaches outside the class, and this
                // handler's whole contract is that one broken subscriber cannot
                // derail the others. Evicting first keeps the registry correct
                // even if that call fails.
                log.warn("Failed to send update for session {}, evicting subscriber", sessionId, e);
                evict(sessionId, emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * Construction seam: the one place that knows how an emitter for this
     * publisher is built. Package-private so it can be overridden from within
     * this package.
     */
    SseEmitter createEmitter() {
        return new SseEmitter(timeoutMs);
    }

    @Override
    public SseEmitter subscribe(String sessionId, SessionRecord currentState) {
        SseEmitter emitter = createEmitter();

        // Registration happens inside compute() rather than as
        // `computeIfAbsent(...).add(emitter)`: the latter adds *after* the map
        // call returns, so a concurrent evict of the last subscriber can empty
        // the set and drop the whole entry in between — leaving this emitter in
        // a set no longer reachable from the registry, silently receiving
        // nothing. compute() holds the bin lock across the add, so registration
        // and eviction cannot interleave.
        registry.compute(sessionId, (key, existing) -> {
            Set<SseEmitter> emitters = (existing != null) ? existing : ConcurrentHashMap.newKeySet();
            emitters.add(emitter);
            return emitters;
        });

        emitter.onCompletion(() -> evict(sessionId, emitter));
        emitter.onTimeout(() -> evict(sessionId, emitter));
        emitter.onError(ex -> evict(sessionId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .id(eventId(currentState))
                    .data(SessionResponse.from(currentState)));

            if (currentState.status() == SessionStatus.COMPLETED
                    || currentState.status() == SessionStatus.FAILED) {
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            }
        } catch (Exception e) {
            // Initial-send failure. Spring has not initialized this emitter yet,
            // so the send is buffered rather than written: nothing here can fail
            // on I/O, and reaching this branch means a defect in building the
            // payload. The subscriber is evicted so the registry does not retain
            // an emitter nothing will ever publish to. Logged at WARN because a
            // failed subscription is rare and worth investigating.
            log.warn("Failed to send initial state to session {}, evicting subscriber", sessionId, e);
            evict(sessionId, emitter);
        }

        return emitter;
    }

    /**
     * Computes the event id for a session record. Empty history → {@code "0"}
     * (the initial state), otherwise the move count.
     */
    static String eventId(SessionRecord record) {
        return record.moveHistory().isEmpty() ? "0" : String.valueOf(record.moveHistory().size());
    }

    /**
     * Removes a subscriber, dropping the session's entry once its last
     * subscriber is gone. Removal and the empty check run inside {@code
     * computeIfPresent} so they are atomic with respect to {@link #subscribe} —
     * see the comment there. Returning {@code null} from the remapping function
     * is what removes the key.
     */
    void evict(String sessionId, SseEmitter emitter) {
        registry.computeIfPresent(sessionId, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
