package com.flamingo.tiktaktoe.session.store;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.publisher.GameUpdatePublisher;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PublishingSessionStore}: the decorator must delegate
 * every call to the wrapped store, fire the publisher after {@code save}
 * (and only after — the save must never be lost because notification failed),
 * and leave {@code find}/{@code claimForRunning} untouched.
 */
@ExtendWith(MockitoExtension.class)
class PublishingSessionStoreTest {

    @Mock
    private SessionStore delegate;

    @Mock
    private GameUpdatePublisher publisher;

    @InjectMocks
    private PublishingSessionStore store;

    private static SessionRecord sampleRecord(String id, SessionStatus status) {
        return new SessionRecord(id, status, null, List.of());
    }

    // ---- save ----

    @Test
    void save_delegatesToWrappedStore() {
        SessionRecord record = sampleRecord("s1", SessionStatus.RUNNING);
        when(delegate.save(record)).thenReturn(record);

        store.save(record);

        verify(delegate).save(record);
    }

    @Test
    void save_publishesAfterDelegateSucceeds() {
        SessionRecord record = sampleRecord("s1", SessionStatus.RUNNING);
        when(delegate.save(record)).thenReturn(record);

        store.save(record);

        InOrder order = inOrder(delegate, publisher);
        order.verify(delegate).save(record);
        order.verify(publisher).publish("s1", record);
    }

    @Test
    void save_whenPublisherThrows_doesNotLoseTheSave() {
        SessionRecord record = sampleRecord("s1", SessionStatus.RUNNING);
        when(delegate.save(record)).thenReturn(record);
        doThrow(new RuntimeException("emitter failed")).when(publisher).publish("s1", record);

        SessionRecord result = store.save(record);

        assertThat(result).isSameAs(record);
        verify(delegate).save(record);
    }

    @Test
    void save_passesTheRecordReturnedByTheDelegate_toThePublisher() {
        SessionRecord input = sampleRecord("s1", SessionStatus.CREATED);
        SessionRecord delegated = sampleRecord("s1", SessionStatus.RUNNING);
        when(delegate.save(input)).thenReturn(delegated);

        store.save(input);

        verify(publisher).publish("s1", delegated);
    }

    @Test
    void save_whenPublisherThrows_logsTheThrowableSoTheStackTraceSurvives() {
        Logger storeLogger = (Logger) LoggerFactory.getLogger(PublishingSessionStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        storeLogger.addAppender(appender);
        try {
            SessionRecord record = sampleRecord("s1", SessionStatus.RUNNING);
            when(delegate.save(record)).thenReturn(record);
            doThrow(new IllegalStateException("emitter failed")).when(publisher).publish("s1", record);

            store.save(record);

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("s1");
            // The throwable must reach the appender as a throwable, not as an
            // interpolated string: only then does the operator get a stack trace.
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(IllegalStateException.class.getName());
        } finally {
            storeLogger.detachAppender(appender);
        }
    }

    // ---- find ----

    @Test
    void find_delegatesToWrappedStore() {
        SessionRecord record = sampleRecord("s1", SessionStatus.RUNNING);
        when(delegate.find("s1")).thenReturn(record);

        assertThat(store.find("s1")).isSameAs(record);
        verify(delegate).find("s1");
    }

    @Test
    void find_doesNotPublish() {
        store.find("s1");

        verify(publisher, never()).publish(any(), any());
    }

    // ---- claimForRunning ----

    @Test
    void claimForRunning_delegatesToWrappedStore() {
        SessionRecord record = sampleRecord("s1", SessionStatus.RUNNING);
        when(delegate.claimForRunning("s1")).thenReturn(record);

        assertThat(store.claimForRunning("s1")).isSameAs(record);
        verify(delegate).claimForRunning("s1");
    }

    @Test
    void claimForRunning_doesNotPublish() {
        when(delegate.claimForRunning("s1")).thenReturn(sampleRecord("s1", SessionStatus.RUNNING));

        store.claimForRunning("s1");

        verify(publisher, never()).publish(any(), any());
    }
}
