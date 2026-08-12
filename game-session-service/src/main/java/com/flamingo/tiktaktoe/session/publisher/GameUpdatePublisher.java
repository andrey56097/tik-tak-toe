package com.flamingo.tiktaktoe.session.publisher;

import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Port for pushing session state updates to subscribers. The update channel is
 * strictly one-way (server → browser), so SSE is the natural fit; a WebSocket
 * implementation would implement the same interface and swap in with zero
 * changes to anything upstream.
 *
 * <p>{@link com.flamingo.tiktaktoe.session.store.PublishingSessionStore}
 * calls {@link #publish} after every state transition; the controller calls
 * {@link #subscribe} when a client opens the stream endpoint.
 */
public interface GameUpdatePublisher {

    /**
     * Pushes the current session state to every subscriber of that session.
     * When there are no subscribers this is a no-op, not a failure — the
     * publisher does not require listeners.
     *
     * @param sessionId the session whose state changed
     * @param record    the session record after the change
     */
    void publish(String sessionId, SessionRecord record);

    /**
     * Registers a new subscriber for a session and sends the current state as
     * the very first event so the client is never looking at a blank page.
     *
     * @param sessionId    the session to subscribe to
     * @param currentState the session's current record at subscription time
     * @return an {@link SseEmitter} that the controller returns directly
     */
    SseEmitter subscribe(String sessionId, SessionRecord currentState);
}
