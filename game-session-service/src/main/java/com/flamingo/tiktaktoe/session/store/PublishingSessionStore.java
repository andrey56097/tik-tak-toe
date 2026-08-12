package com.flamingo.tiktaktoe.session.store;

import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.publisher.GameUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Decorator over {@link SessionStore} that publishes every state transition.
 *
 * <p>Every call to {@link #save} delegates to the wrapped store, then fires
 * the publisher — so any future code path that calls {@code save} is
 * automatically covered, and a transition cannot forget to notify. A publisher
 * failure is logged but never propagated: the save must not be lost because
 * notification failed.
 *
 * <p>Marked {@code @Primary} so {@link
 * com.flamingo.tiktaktoe.session.service.SessionSimulationRunner} and
 * {@link com.flamingo.tiktaktoe.session.orchestrator.GameSessionOrchestrator}
 * receive this bean through their existing {@code SessionStore} injection
 * points without a single line changed.
 */
@Component
@Primary
public class PublishingSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(PublishingSessionStore.class);

    private final SessionStore delegate;
    private final GameUpdatePublisher publisher;

    public PublishingSessionStore(
            @Qualifier("inMemorySessionStore") SessionStore delegate,
            GameUpdatePublisher publisher) {
        this.delegate = delegate;
        this.publisher = publisher;
    }

    @Override
    public SessionRecord save(SessionRecord record) {
        SessionRecord saved = delegate.save(record);
        try {
            publisher.publish(record.sessionId(), saved);
        } catch (Exception e) {
            // The throwable is passed, not interpolated: a publisher failure is a
            // server-side defect, and the operator needs the stack trace to place it.
            log.warn("Failed to publish update for session {}", record.sessionId(), e);
        }
        return saved;
    }

    @Override
    public SessionRecord find(String sessionId) {
        return delegate.find(sessionId);
    }

    @Override
    public SessionRecord claimForRunning(String sessionId) {
        return delegate.claimForRunning(sessionId);
    }
}
