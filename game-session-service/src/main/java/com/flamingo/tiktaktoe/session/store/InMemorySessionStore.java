package com.flamingo.tiktaktoe.session.store;

import com.flamingo.tiktaktoe.session.domain.SessionRecord;
import com.flamingo.tiktaktoe.session.domain.SessionStatus;
import com.flamingo.tiktaktoe.session.exception.SessionCapacityException;
import com.flamingo.tiktaktoe.session.exception.SessionConflictException;
import com.flamingo.tiktaktoe.session.exception.SessionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Component
public class InMemorySessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySessionStore.class);

    private record Entry(SessionRecord record, Instant lastUpdated) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final SessionRetentionPolicy policy;
    private final Clock clock;
    private final Semaphore sessionPermits;

    @Autowired
    public InMemorySessionStore(
            @Value("${session.store.terminal-retention-ms}") long terminalRetentionMs,
            @Value("${session.store.max-sessions}") int maxSessions) {
        this(new SessionRetentionPolicy(Duration.ofMillis(terminalRetentionMs), maxSessions),
                Clock.systemUTC());
    }

    public InMemorySessionStore(SessionRetentionPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
        this.sessionPermits = new Semaphore(policy.maxSessions());
    }

    @Override
    public SessionRecord save(SessionRecord record) {
        String id = record.sessionId();
        entries.compute(id, (key, existing) -> {
            if (existing != null) {
                return new Entry(record, clock.instant());
            }
            if (!sessionPermits.tryAcquire()) {
                throw new SessionCapacityException(
                        "Session capacity reached (" + policy.maxSessions() + "); try again later");
            }
            return new Entry(record, clock.instant());
        });
        return record;
    }

    @Override
    public SessionRecord find(String sessionId) {
        Entry entry = entries.get(sessionId);
        return entry == null ? null : entry.record();
    }

    @Override
    public SessionRecord claimForRunning(String sessionId) {
        return entries.compute(sessionId, (id, existing) -> {
            if (existing == null) {
                throw new SessionNotFoundException(id);
            }
            if (existing.record().status() != SessionStatus.CREATED) {
                throw new SessionConflictException("Session " + id + " cannot be started: current status is "
                        + existing.record().status());
            }
            SessionRecord claimed = new SessionRecord(id, SessionStatus.RUNNING,
                    existing.record().gameState(), existing.record().moveHistory());
            return new Entry(claimed, clock.instant());
        }).record();
    }

    int evictExpired(Instant now) {
        int evicted = 0;
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            Entry value = entry.getValue();
            if (isTerminal(value.record().status())
                    && value.lastUpdated().plus(policy.terminalRetention()).isBefore(now)
                    && entries.remove(entry.getKey(), value)) {
                sessionPermits.release();
                evicted++;
            }
        }
        return evicted;
    }

    @Scheduled(fixedDelayString = "${session.store.sweep-interval-ms}")
    void sweep() {
        int evicted = evictExpired(clock.instant());
        if (evicted > 0) {
            log.debug("Evicted {} terminal session(s) past retention", evicted);
        }
    }

    private static boolean isTerminal(SessionStatus status) {
        return status == SessionStatus.COMPLETED || status == SessionStatus.FAILED;
    }
}
