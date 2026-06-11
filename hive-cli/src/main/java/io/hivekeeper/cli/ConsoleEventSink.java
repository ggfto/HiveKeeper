package io.hivekeeper.cli;

import io.hivekeeper.core.api.Event;
import io.hivekeeper.core.api.EventSink;

/** Renders streamed {@link Event}s to stderr so stdout stays clean for the final result. */
final class ConsoleEventSink implements EventSink {

    @Override
    public void emit(Event event) {
        switch (event) {
            case Event.Started s -> System.err.println("> " + s.label());
            case Event.Progress p -> System.err.printf("  [%3s%%] %s%n",
                    p.percent() == null ? "?" : p.percent().toString(), p.message());
            case Event.Log l -> System.err.println("  . " + l.line());
            case Event.Failed f -> System.err.println("  x " + f.error() + ": " + f.detail());
            case Event.Completed c -> { /* final result is printed by the command */ }
        }
    }
}
