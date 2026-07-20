package io.hivekeeper.core.engine;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.drivers.DriverRegistry;
import io.hivekeeper.core.model.PpskUserRecord;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import io.hivekeeper.core.spi.PpskUserStore;
import io.hivekeeper.core.spi.SecretUnsealer;
import io.hivekeeper.core.transport.SshTransport;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives {@link LocalEngine#execute} for {@link Command.ManagePpskUser} with in-memory fakes — no network,
 *  no real crypto. Pins the agent-control behavior: unseal the PSK locally, write the on-prem store, never
 *  open an SSH session to the AP. */
class LocalEngineManagePpskUserTest {

    /** Fails the test if any code path opens an SSH session — PPSK management must never touch the AP. */
    private final SshTransport transport = (device, creds) -> {
        throw new AssertionError("manage-ppsk-user must not open an SSH session");
    };
    private final CredentialProvider current = ref -> Optional.of(new Credentials("admin", "x"));
    private final DriverRegistry drivers = new DriverRegistry(List.of());
    private final BackupStore store = snapshot -> null;
    // The unsealer stands in for EnvelopeCipher + private key: it recovers username\npsk from the token.
    private final SecretUnsealer unsealer =
            token -> io.hivekeeper.core.crypto.CredentialPayload.encode("alice", "RECOVERED-" + token);

    /** An in-memory PPSK store keyed by security-object + username. */
    private static final class MemoryPpskStore implements PpskUserStore {
        final Map<String, PpskUserRecord> byKey = new LinkedHashMap<>();

        @Override
        public void put(PpskUserRecord record) {
            byKey.put(record.key(), record);
        }

        @Override
        public boolean remove(String securityObject, String username) {
            return byKey.remove(PpskUserRecord.key(securityObject, username)) != null;
        }

        @Override
        public List<PpskUserRecord> list() {
            return new ArrayList<>(byKey.values());
        }
    }

    private Engine engine(PpskUserStore ppsk) {
        return new LocalEngine(transport, current, drivers, store, null, null, unsealer, ppsk);
    }

    @Test
    void createUnsealsThePskLocallyAndWritesTheStoreWithItsPolicy() {
        MemoryPpskStore ppsk = new MemoryPpskStore();
        Result result = engine(ppsk).execute(
                Command.ManagePpskUser.create("Corp", "staff", "alice", "sealed-token", 99, 30, "biz-hours",
                        List.of("aa:bb:cc:dd:ee:ff")),
                ev -> { });

        Result.PpskUserManaged managed = assertInstanceOf(Result.PpskUserManaged.class, result);
        assertEquals("alice", managed.username());
        assertEquals("Corp", managed.securityObject());
        assertEquals("active", managed.status());

        PpskUserRecord stored = ppsk.byKey.get(PpskUserRecord.key("Corp", "alice"));
        assertEquals("RECOVERED-sealed-token", stored.psk());
        assertEquals("staff", stored.userGroup());
        assertEquals(99, stored.userProfileAttr());
        assertEquals(30, stored.vlanId());
        assertEquals("biz-hours", stored.scheduleName());
        assertEquals(List.of("aa:bb:cc:dd:ee:ff"), stored.macBindings());
    }

    @Test
    void rotateReplacesTheStoredKeyForTheSameUser() {
        MemoryPpskStore ppsk = new MemoryPpskStore();
        Engine engine = engine(ppsk);
        engine.execute(Command.ManagePpskUser.create("Corp", "staff", "alice", "t1", null, null, null, List.of()),
                ev -> { });
        engine.execute(Command.ManagePpskUser.rotate("Corp", "staff", "alice", "t2", null, null, null, List.of()),
                ev -> { });

        assertEquals(1, ppsk.byKey.size());
        assertEquals("RECOVERED-t2", ppsk.byKey.get(PpskUserRecord.key("Corp", "alice")).psk());
    }

    @Test
    void revokeRemovesTheUserAndCarriesNoKey() {
        MemoryPpskStore ppsk = new MemoryPpskStore();
        Engine engine = engine(ppsk);
        engine.execute(Command.ManagePpskUser.create("Corp", "staff", "alice", "t1", null, null, null, List.of()),
                ev -> { });

        Result result = engine.execute(Command.ManagePpskUser.revoke("Corp", "alice"), ev -> { });

        Result.PpskUserManaged managed = assertInstanceOf(Result.PpskUserManaged.class, result);
        assertEquals("revoked", managed.status());
        assertTrue(ppsk.byKey.isEmpty());
    }

    @Test
    void managePpskUserIsRefusedWhenTheStoreIsNotEnabled() {
        Engine engine = new LocalEngine(transport, current, drivers, store, null, null, unsealer, null);
        assertThrows(HiveException.class, () -> engine.execute(
                Command.ManagePpskUser.revoke("Corp", "alice"), ev -> { }));
    }

    @Test
    void neitherTheResultNorTheStoredRecordLeakTheKeyInItsStringForm() {
        MemoryPpskStore ppsk = new MemoryPpskStore();
        Result result = engine(ppsk).execute(
                Command.ManagePpskUser.create("Corp", "staff", "alice", "super-secret", null, null, null, List.of()),
                ev -> { });
        // The PSK is held in the store but neither the result nor the record's toString leaks it into logs.
        assertFalse(result.toString().contains("RECOVERED-super-secret"), result.toString());
        assertFalse(ppsk.byKey.get(PpskUserRecord.key("Corp", "alice")).toString().contains("RECOVERED-super-secret"),
                ppsk.byKey.get(PpskUserRecord.key("Corp", "alice")).toString());
    }
}
