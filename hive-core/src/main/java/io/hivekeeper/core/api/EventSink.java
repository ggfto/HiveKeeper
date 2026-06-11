package io.hivekeeper.core.api;

/**
 * Sink for streamed execution events. This is the seam between an in-process caller (a CLI spinner)
 * and a remote one (SSE to a browser, or an agent streaming over a websocket). Events are plain data
 * DTOs, never in-JVM callbacks that hold live state — so the exact same stream survives serialization
 * across an agent channel.
 */
@FunctionalInterface
public interface EventSink {

    void emit(Event event);

    /** Discards all events. */
    EventSink NOOP = event -> { };
}
