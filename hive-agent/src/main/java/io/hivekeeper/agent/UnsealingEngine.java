package io.hivekeeper.agent;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.crypto.EnvelopeCipher;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.wire.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

/**
 * Wraps the on-prem engine to transparently unwrap {@link Command.Sealed}: the cloud seals a secret-bearing
 * command (an SSID passphrase, a hive password) to this agent's public key so the gateway never holds it in a
 * readable form at rest. This engine unseals the wrapper with the agent's private key, deserializes the inner
 * command, and runs it on the real engine. A plain (unsealed) command passes straight through, so the
 * synchronous path and non-secret jobs are unaffected.
 */
@Slf4j
final class UnsealingEngine implements Engine {

    private final Engine delegate;
    private final PrivateKey privateKey;   // nullable — then only plain1: dev tokens can be unsealed
    private final EnvelopeCipher envelope;
    private final JsonCodec codec;

    UnsealingEngine(Engine delegate, PrivateKey privateKey, EnvelopeCipher envelope, JsonCodec codec) {
        this.delegate = delegate;
        this.privateKey = privateKey;
        this.envelope = envelope;
        this.codec = codec;
    }

    @Override
    public Result execute(Command command, EventSink sink) throws HiveException {
        if (!(command instanceof Command.Sealed sealed)) {
            return delegate.execute(command, sink);
        }
        Command inner;
        try {
            byte[] json = envelope.unseal(privateKey, sealed.sealedCommand());
            inner = codec.fromJson(new String(json, StandardCharsets.UTF_8), Command.class);
        } catch (RuntimeException e) {
            // Never log the token or its plaintext — only that the unwrap failed.
            log.warn("failed to unwrap a sealed command: {}", e.getMessage());
            throw new HiveException(command.commandId(), DeviceId.of("sealed"),
                    "failed to unwrap a sealed command (wrong key or corrupt token)", e);
        }
        return delegate.execute(inner, sink);
    }
}
