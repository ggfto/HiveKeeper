package io.hivekeeper.core.drivers;

/**
 * Coarse progress callback a {@link Driver} emits while doing multi-step work. Kept deliberately free
 * of {@code api.Event} so drivers stay decoupled from the eventing/wire layer — the engine adapts this
 * to the real {@code EventSink}.
 */
@FunctionalInterface
public interface ProgressReporter {

    void report(int percent, String message);

    ProgressReporter NOOP = (percent, message) -> { };
}
