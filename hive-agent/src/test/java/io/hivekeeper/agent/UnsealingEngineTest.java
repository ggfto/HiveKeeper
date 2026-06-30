package io.hivekeeper.agent;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.crypto.EnvelopeCipher;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.SsidSpec;
import io.hivekeeper.wire.JsonCodec;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins that the agent unwraps a {@link Command.Sealed} (the gateway's durable-job sealing) back to the inner
 *  command with its secret intact, and passes plain commands straight through. */
class UnsealingEngineTest {

    private final EnvelopeCipher envelope = new EnvelopeCipher();
    private final JsonCodec codec = new JsonCodec();

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    /** A delegate that records the command it was asked to run. */
    private static final class Recording implements Engine {
        Command received;

        @Override
        public Result execute(Command command, EventSink sink) {
            received = command;
            return new Result.ConfigApplied(command.commandId(), DeviceId.of("x"), java.util.List.of(),
                    java.util.List.of(), true);
        }
    }

    @Test
    void unwrapsASealedCommandToItsInnerCommandWithTheSecretIntact() throws Exception {
        KeyPair agent = rsa();
        Command inner = Command.ConfigureSsid.of(DeviceRef.ssh("10.0.0.1", 22, "cred"),
                SsidSpec.create("Corp", "s3cret-psk", 30));
        String token = envelope.seal(agent.getPublic(),
                codec.toJson(inner).getBytes(StandardCharsets.UTF_8));

        Recording delegate = new Recording();
        new UnsealingEngine(delegate, agent.getPrivate(), envelope, codec)
                .execute(Command.Sealed.of(token), EventSink.NOOP);

        Command.ConfigureSsid got = assertInstanceOf(Command.ConfigureSsid.class, delegate.received);
        assertEquals("Corp", got.spec().name());
        assertEquals("s3cret-psk", got.spec().passphrase());
        assertEquals(30, got.spec().vlan());
    }

    @Test
    void passesAPlainCommandStraightThrough() throws Exception {
        Recording delegate = new Recording();
        Command plain = Command.Inventory.of(DeviceRef.ssh("10.0.0.1", 22, "cred"));
        new UnsealingEngine(delegate, rsa().getPrivate(), envelope, codec).execute(plain, EventSink.NOOP);
        assertEquals(plain, delegate.received);
    }

    @Test
    void aTokenSealedToAnotherKeyIsRejected() throws Exception {
        String token = envelope.seal(rsa().getPublic(),
                codec.toJson(Command.Inventory.of(DeviceRef.ssh("10.0.0.1"))).getBytes(StandardCharsets.UTF_8));
        Recording delegate = new Recording();
        Engine engine = new UnsealingEngine(delegate, rsa().getPrivate(), envelope, codec);
        assertThrows(HiveException.class, () -> engine.execute(Command.Sealed.of(token), EventSink.NOOP));
    }

    @Test
    void plainDevTokenRoundTripsWithoutAKey() {
        // no recipient key -> plain1: dev fallback; unsealable with a null private key.
        Command inner = Command.ConfigureHive.of(DeviceRef.ssh("10.0.0.1", 22, "cred"),
                new io.hivekeeper.core.model.HiveSpec("hq", "hive-pass", null));
        String token = envelope.seal(null, codec.toJson(inner).getBytes(StandardCharsets.UTF_8));
        AtomicReference<Command> seen = new AtomicReference<>();
        Engine delegate = (c, s) -> {
            seen.set(c);
            return new Result.ConfigApplied(c.commandId(), DeviceId.of("x"), java.util.List.of(), java.util.List.of(), true);
        };
        new UnsealingEngine(delegate, null, envelope, codec).execute(Command.Sealed.of(token), EventSink.NOOP);
        assertEquals("hive-pass", ((Command.ConfigureHive) seen.get()).spec().password());
    }
}
