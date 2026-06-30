package io.hivekeeper.agent.radius;

import io.hivekeeper.core.model.PpskUserRecord;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

/**
 * Provisions a co-located FreeRADIUS by writing its {@code files}-module authorize file (the classic
 * {@code mods-config/files/authorize} / "users" format). The mechanism is standard RADIUS PAP: with
 * {@code security-object <so> security private-psk radius-auth pap} the AP forwards the registrant's
 * submitted Private PSK to RADIUS as the password, and RADIUS matches it against {@code Cleartext-Password}
 * and returns the user's VLAN via the RFC 2868 tunnel attributes.
 *
 * <p>The PAP match and the {@code Tunnel-*} VLAN reply are RFC-standard (not guessed). The one piece still
 * to confirm live is the <b>Aerohive user-profile VSA</b> (returning a named user-profile on Accept): it is
 * emitted as a documented comment until captured from a real AP exchange — see the PPSK-via-RADIUS design
 * doc, milestone 4. Provisioning is idempotent and always rendered from the complete user set, so a crash
 * mid-write never leaves a partial state (the file is written atomically via a temp file + move).
 */
@Slf4j
public final class FreeRadiusFilesProvisioner implements RadiusProvisioner {

    /** The FreeRADIUS files-module authorize file this writes (mount/point FreeRADIUS at it). */
    public static final String AUTHORIZE_FILE = "authorize";

    private final Path dir;

    public FreeRadiusFilesProvisioner(Path dir) {
        this.dir = dir;
    }

    @Override
    public void provision(List<PpskUserRecord> activeUsers) {
        String content = render(activeUsers);
        Path file = dir.resolve(AUTHORIZE_FILE);
        try {
            Files.createDirectories(dir);
            Path tmp = file.resolveSibling(AUTHORIZE_FILE + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            log.info("provisioned {} PPSK user(s) to FreeRADIUS authorize file {}", activeUsers.size(), file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write the FreeRADIUS authorize file " + file, e);
        }
    }

    /**
     * Renders the FreeRADIUS {@code files} authorize content for the given users. Pure and deterministic
     * (users sorted by security-object then username) so it is unit-testable. Only {@code active} records are
     * emitted; revoked users simply disappear from the file.
     */
    public static String render(List<PpskUserRecord> users) {
        StringBuilder sb = new StringBuilder();
        sb.append("# HiveKeeper PPSK users — generated; do not edit. Source of truth: the agent PPSK store.\n");
        sb.append("# FreeRADIUS files module. PAP: the AP forwards the submitted Private PSK as the password;\n");
        sb.append("# RADIUS matches Cleartext-Password and returns the VLAN via RFC 2868 tunnel attributes.\n\n");
        users.stream()
                .filter(u -> u.status() == null || "active".equalsIgnoreCase(u.status()))
                .sorted(Comparator.comparing(PpskUserRecord::securityObject).thenComparing(PpskUserRecord::username))
                .forEach(u -> appendUser(sb, u));
        return sb.toString();
    }

    private static void appendUser(StringBuilder sb, PpskUserRecord u) {
        sb.append(u.username()).append(" Cleartext-Password := \"").append(escape(u.psk())).append("\"\n");
        if (u.vlanId() != null) {
            sb.append("    Tunnel-Type := VLAN,\n");
            sb.append("    Tunnel-Medium-Type := IEEE-802,\n");
            // Last real reply attribute, so no trailing comma (the VSA below is only a comment for now).
            sb.append("    Tunnel-Private-Group-Id := \"").append(u.vlanId()).append("\"\n");
        }
        if (u.userProfileAttr() != null) {
            // The Aerohive user-profile VSA name is pending live capture (design doc, milestone 4). Documented,
            // not emitted, so an unconfirmed attribute never reaches a live RADIUS config.
            sb.append("    # Aerohive user-profile attr ").append(u.userProfileAttr())
                    .append(" — VSA name pending live capture (M4)\n");
        }
        sb.append('\n');
    }

    private static String escape(String psk) {
        return psk == null ? "" : psk.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
