package io.hivekeeper.agent.radius;

import io.hivekeeper.core.model.PpskUserRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the FreeRADIUS files-module rendering: standard PAP Cleartext-Password + RFC 2868 VLAN tunnel
 *  attributes, revoked users omitted, deterministic order, and an atomic file write. */
class FreeRadiusFilesProvisionerTest {

    @Test
    void rendersPapPasswordAndRfcVlanAttributes() {
        String out = FreeRadiusFilesProvisioner.render(List.of(
                new PpskUserRecord("Corp", "staff", "alice", "psk-aaa", 99, 30, null, List.of(), "active")));
        assertTrue(out.contains("alice Cleartext-Password := \"psk-aaa\""), out);
        assertTrue(out.contains("Tunnel-Type := VLAN"), out);
        assertTrue(out.contains("Tunnel-Medium-Type := IEEE-802"), out);
        assertTrue(out.contains("Tunnel-Private-Group-Id := \"30\""), out);
        // The Aerohive user-profile VSA is documented (a comment), never emitted as an unconfirmed attribute.
        assertTrue(out.contains("# Aerohive user-profile attr 99"), out);
        assertFalse(out.contains("Tunnel-Private-Group-Id := \"30\",\n    #"), "no trailing comma before the VSA comment");
    }

    @Test
    void omitsTheVlanBlockWhenNoVlanIsSet() {
        String out = FreeRadiusFilesProvisioner.render(List.of(
                new PpskUserRecord("Open", null, "guest", "pw", null, null, null, List.of(), "active")));
        assertTrue(out.contains("guest Cleartext-Password := \"pw\""), out);
        assertFalse(out.contains("Tunnel-Type"), out);
    }

    @Test
    void omitsRevokedUsersAndSortsDeterministically() {
        String out = FreeRadiusFilesProvisioner.render(List.of(
                new PpskUserRecord("Corp", "staff", "zoe", "p", null, null, null, List.of(), "active"),
                new PpskUserRecord("Corp", "staff", "amy", "p", null, null, null, List.of(), "active"),
                new PpskUserRecord("Corp", "staff", "ben", "p", null, null, null, List.of(), "revoked")));
        assertFalse(out.contains("ben "), "revoked users must not be emitted");
        assertTrue(out.indexOf("amy ") < out.indexOf("zoe "), "users sorted by username");
    }

    @Test
    void escapesQuotesInThePsk() {
        String out = FreeRadiusFilesProvisioner.render(List.of(
                new PpskUserRecord("Corp", null, "ed", "a\"b", null, null, null, List.of(), "active")));
        assertTrue(out.contains("Cleartext-Password := \"a\\\"b\""), out);
    }

    @Test
    void provisionWritesTheAuthorizeFile(@TempDir Path dir) throws IOException {
        new FreeRadiusFilesProvisioner(dir).provision(List.of(
                new PpskUserRecord("Corp", "staff", "alice", "psk-aaa", null, 30, null, List.of(), "active")));
        Path file = dir.resolve(FreeRadiusFilesProvisioner.AUTHORIZE_FILE);
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file).contains("alice Cleartext-Password"));
    }
}
