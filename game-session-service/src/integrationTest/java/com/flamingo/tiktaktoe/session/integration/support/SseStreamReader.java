package com.flamingo.tiktaktoe.session.integration.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A real streaming SSE reader over a real socket, used by {@code SessionEngineSseIT}
 * to prove that Session's stream delivers one event per move <strong>as the moves
 * happen</strong>. The connection, the incremental parsing and the end-of-stream
 * are all real — this is not MockMvc replaying a captured body.
 *
 * <p><strong>Why a background reader thread feeding a bounded queue.</strong>
 * {@link BufferedReader#readLine()} blocks indefinitely, and a live stream's
 * defining property is that the server may hold the connection open with nothing
 * on it for a long time. A test that called {@code readLine()} directly could
 * therefore hang forever on a stream that never produces the next event — the
 * exact failure this milestone exists to catch. Reading on a daemon thread and
 * polling a {@link BlockingQueue} with a deadline turns that into a bounded read:
 * the caller waits at most the given budget, and a stream that never produces what
 * it should fails with a message naming the stream and the budget, never with a
 * hung test.
 *
 * <p><strong>What it parses.</strong> The SSE framing — {@code id:}, {@code event:}
 * and {@code data:} lines, a blank line ends an event — which is the wire contract
 * of {@code SseGameUpdatePublisher}: the {@code id} is the session's move count,
 * the {@code data} is a full {@code SessionResponse} JSON document, and a named
 * {@code event:done} event precedes the server completing (closing) the connection.
 *
 * <p><strong>Lifecycle.</strong> The reader thread is a daemon, so a test that
 * fails early cannot keep the test JVM alive on a still-open connection; {@link
 * #close()} closes the body stream, which unblocks the reader thread if the server
 * never closed, so the connection is always released.
 */
public final class SseStreamReader implements AutoCloseable {

    /**
     * One parsed SSE event. {@code id} and {@code name} are {@code null}-free (an
     * event that never sent the line carries an empty string); {@code data} is the
     * joined {@code data:} lines of the event with the trailing newline stripped.
     */
    public record SseEvent(String id, String name, String data) {

        /** True for the publisher's terminal {@code event:done} marker. */
        public boolean isDone() {
            return "done".equals(name);
        }
    }

    /**
     * Raised by {@link #readEvent(Duration)} when the budget expires with neither
     * an event nor a clean end-of-stream — the stream never produced what the
     * caller needed (typically the {@code done} event) in time.
     */
    public static final class TimeoutException extends RuntimeException {
        TimeoutException(String message) {
            super(message);
        }
    }

    private final String description;

    private final InputStream body;

    private final BlockingQueue<SseEvent> events = new LinkedBlockingQueue<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final AtomicReference<Throwable> transportFailure = new AtomicReference<>();

    private SseStreamReader(String description, InputStream body, BufferedReader reader) {
        this.description = description;
        this.body = body;
        Thread.ofPlatform()
                .daemon()
                .name("sse-reader")
                .start(() -> readLoop(reader, events, closed, transportFailure));
    }

    /**
     * Opens {@code streamUri} (the session's {@code /sessions/{id}/stream} URL)
     * over real HTTP and returns a reader that yields the events as they arrive.
     * Blocks until the response headers are in — the publisher writes the current
     * state as the first event during subscribe, so by the time this returns the
     * connection is established and that first event is on its way.
     *
     * @param streamUri     the full stream URL
     * @param connectBudget how long to wait for the connection and response headers
     * @throws TimeoutException      if the stream does not answer within the budget
     * @throws IllegalStateException if the response is not a 200 {@code text/event-stream}
     */
    public static SseStreamReader open(URI streamUri, Duration connectBudget)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(connectBudget).build();
        HttpRequest request = HttpRequest.newBuilder(streamUri)
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        final HttpResponse<InputStream> response;
        try {
            response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .get(connectBudget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new TimeoutException("GET " + streamUri + " sent no response headers within "
                    + connectBudget + " — is the stream endpoint answering at all?");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IllegalStateException("GET " + streamUri + " failed to connect", e);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET " + streamUri + " returned HTTP "
                    + response.statusCode() + " instead of a 200 text/event-stream stream");
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.startsWith("text/event-stream")) {
            throw new IllegalStateException("GET " + streamUri + " returned Content-Type \""
                    + contentType + "\" — expected text/event-stream");
        }

        InputStream body = response.body();
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        return new SseStreamReader("SSE stream at " + streamUri, body, reader);
    }

    /**
     * Waits up to {@code budget} for the next event.
     *
     * @return the next parsed event, or {@code null} when the stream has reached a
     * clean end-of-stream and the queue is drained — i.e. the server closed the
     * connection (the publisher completed the emitter after {@code done})
     * @throws TimeoutException if the budget expires with neither an event nor a
     *                          clean end-of-stream
     */
    public SseEvent readEvent(Duration budget) {
        long deadlineNanos = System.nanoTime() + budget.toNanos();
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("no SSE event within " + budget + " from " + description
                        + (closed.get() ? " (stream already at its end)" : " (stream still open)"));
            }
            SseEvent event;
            try {
                event = events.poll(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TimeoutException("interrupted while reading " + description);
            }
            if (event != null) {
                return event;
            }
            if (closed.get()) {
                return null; // end of stream and the queue is drained
            }
        }
    }

    /**
     * @return whether the reader thread has reached the end of the stream (clean
     * EOF or an I/O error — {@link #transportFailure()} says which)
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * @return the transport failure, if the stream ended in an I/O error rather
     * than a clean EOF (empty when the server completed the connection normally)
     */
    public java.util.Optional<Throwable> transportFailure() {
        return java.util.Optional.ofNullable(transportFailure.get());
    }

    /**
     * Releases the connection: closes the body stream, which unblocks the reader
     * thread if it is still waiting on the server. Idempotent — safe to call after
     * a clean end-of-stream.
     */
    @Override
    public void close() throws IOException {
        body.close();
    }

    /**
     * Reads SSE lines incrementally on the reader thread, offering one {@link
     * SseEvent} per blank-line-delimited frame. A clean EOF marks the stream
     * closed; an I/O error is recorded in {@code transportFailure} so a test can
     * distinguish "the server completed the stream" from "the connection broke".
     */
    private static void readLoop(BufferedReader reader, BlockingQueue<SseEvent> queue,
                                 AtomicBoolean closed, AtomicReference<Throwable> transportFailure) {
        StringBuilder id = new StringBuilder();
        StringBuilder name = new StringBuilder();
        StringBuilder data = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // A blank line ends the event.
                    queue.offer(new SseEvent(id.toString(), name.toString(), dataOf(data)));
                    id.setLength(0);
                    name.setLength(0);
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    // SSE comment line — ignored.
                } else {
                    int colon = line.indexOf(':');
                    if (colon < 0) {
                        continue; // not an SSE field we can make sense of
                    }
                    String field = line.substring(0, colon);
                    String value = line.substring(colon + 1);
                    if (value.startsWith(" ")) {
                        value = value.substring(1); // SSE strips one leading space
                    }
                    switch (field) {
                        case "id" -> id.append(value);
                        case "event" -> name.append(value);
                        case "data" -> data.append(value).append('\n');
                        default -> { /* unknown field — ignore, per the SSE spec */ }
                    }
                }
            }
            // Clean EOF — flush a trailing event if the writer ended without a blank line.
            if (id.length() > 0 || name.length() > 0 || data.length() > 0) {
                queue.offer(new SseEvent(id.toString(), name.toString(), dataOf(data)));
            }
        } catch (IOException e) {
            transportFailure.set(e);
        } finally {
            closed.set(true);
        }
    }

    private static String dataOf(StringBuilder data) {
        return data.length() > 0 && data.charAt(data.length() - 1) == '\n'
                ? data.substring(0, data.length() - 1)
                : data.toString();
    }
}
