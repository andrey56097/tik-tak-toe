package com.flamingo.tiktaktoe.session.publisher;

import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Port for pushing session state to subscribers. The channel is one-way
 * (server → browser), so SSE fits; a WebSocket implementation would swap in
 * behind this interface unchanged.
 *
 * <p>{@code PublishingSessionStore} calls {@link #publish} after every state
 * transition; the controller calls {@link #subscribe} when a client connects.
 */
public interface GameUpdatePublisher {

    /** Pushes the state to every subscriber. No subscribers is a no-op, not a failure. */
    void publish(String sessionId, SessionRecord record);

    /**
     * Registers a subscriber and sends the current state as the first event, so a
     * client attaching mid-game is never looking at a blank board.
     */
    SseEmitter subscribe(String sessionId, SessionRecord currentState);
}
