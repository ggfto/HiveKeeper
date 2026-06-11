package io.hivekeeper.core.api;

/**
 * The single entry point for executing work against a device. Every front-end — CLI, local server,
 * and (later) a cloud-driven agent — invokes the engine through this same contract. Local vs remote is
 * which implementation is wired, not a fork: a LocalEngine runs in-process; a future RemoteEngine
 * forwards the same serializable {@link Command} over an agent channel and pumps the remote
 * {@link Event}s into the supplied {@link EventSink}.
 *
 * <p>The terminal outcome is returned as a {@link Result}. Progress and the full lifecycle (including
 * failure, via {@link Event.Failed}) are streamed through {@code sink}. On failure, implementations
 * additionally throw {@link HiveException} for in-process convenience.
 */
public interface Engine {

    Result execute(Command command, EventSink sink) throws HiveException;
}
