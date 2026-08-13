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
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                sendUpdate(emitter, record);
            } catch (IOException e) {
                log.debug("Client disconnected from session {}, evicting subscriber", sessionId, e);
                evict(sessionId, emitter);
            } catch (Exception e) {
                log.warn("Failed to send update for session {}, evicting subscriber", sessionId, e);
                evict(sessionId, emitter);
                emitter.completeWithError(e);
            }
        }
    }

    SseEmitter createEmitter() {
        return new SseEmitter(timeoutMs);
    }

    @Override
    public SseEmitter subscribe(String sessionId, SessionRecord currentState) {
        SseEmitter emitter = createEmitter();

        registry.compute(sessionId, (key, existing) -> {
            Set<SseEmitter> emitters = (existing != null) ? existing : ConcurrentHashMap.newKeySet();
            emitters.add(emitter);
            return emitters;
        });

        emitter.onCompletion(() -> evict(sessionId, emitter));
        emitter.onTimeout(() -> evict(sessionId, emitter));
        emitter.onError(ex -> evict(sessionId, emitter));

        try {
            sendUpdate(emitter, currentState);
        } catch (Exception e) {
            log.warn("Failed to send initial state to session {}, evicting subscriber", sessionId, e);
            evict(sessionId, emitter);
        }

        return emitter;
    }

    static String eventId(SessionRecord record) {
        return record.moveHistory().isEmpty() ? "0" : String.valueOf(record.moveHistory().size());
    }

    private static void sendUpdate(SseEmitter emitter, SessionRecord record) throws IOException {
        emitter.send(SseEmitter.event()
                .id(eventId(record))
                .data(SessionResponse.from(record)));
        if (isTerminal(record)) {
            emitter.send(SseEmitter.event().name("done").data(""));
            emitter.complete();
        }
    }

    private static boolean isTerminal(SessionRecord record) {
        return record.status() == SessionStatus.COMPLETED || record.status() == SessionStatus.FAILED;
    }

    void evict(String sessionId, SseEmitter emitter) {
        registry.computeIfPresent(sessionId, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
