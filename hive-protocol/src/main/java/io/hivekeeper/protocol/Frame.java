package io.hivekeeper.protocol;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Event;
import io.hivekeeper.core.api.Result;

/**
 * The versioned envelope exchanged between an on-prem agent and the cloud gateway over a single
 * outbound WebSocket. It carries the SAME serializable {@link Command}/{@link Event}/{@link Result}
 * DTOs the engine already uses in-process — the wire format IS the serialized in-process API. This type
 * is transport-agnostic (no WebSocket/HTTP here); a {@link FrameChannel} moves frames over whatever
 * medium, and the in-memory channel exists for embedded/loopback use.
 *
 * <p>Direction conventions:
 * <ul>
 *   <li>agent → gateway: {@link Hello}, {@link Resume}, {@link JobEvent}, {@link JobResult},
 *       {@link JobFailed}, {@link Heartbeat}</li>
 *   <li>gateway → agent: {@link Job}, {@link Ack}, {@link Heartbeat}</li>
 * </ul>
 */
public sealed interface Frame
        permits Frame.Hello, Frame.Resume, Frame.Job, Frame.JobEvent, Frame.JobResult,
                Frame.JobFailed, Frame.Ack, Frame.Heartbeat {

    /** Agent identifies itself + the protocol version it speaks, right after connecting. */
    record Hello(String agentId, String protocolVersion) implements Frame {
    }

    /** Agent asks the gateway to redeliver anything after the last job/seq it acknowledged. */
    record Resume(String agentId, String lastJobId, long lastSeq) implements Frame {
    }

    /** A unit of work dispatched to the agent (= a serialized {@link Command} + delivery metadata). */
    record Job(String jobId, String idempotencyKey, long deadlineEpochMs, Command command) implements Frame {
    }

    /** A streamed progress event for a job; {@code seq} is monotonic per job for gap/dup detection. */
    record JobEvent(String jobId, long seq, Event event) implements Frame {
    }

    /** The terminal success outcome of a job. */
    record JobResult(String jobId, Result result) implements Frame {
    }

    /** The terminal failure outcome of a job. */
    record JobFailed(String jobId, String error, String detail) implements Frame {
    }

    /** Gateway acknowledges receipt up to {@code ackedSeq} for a job (enables resume/redelivery). */
    record Ack(String jobId, long ackedSeq) implements Frame {
    }

    /** Keep-alive in either direction. */
    record Heartbeat(long epochMillis) implements Frame {
    }
}
