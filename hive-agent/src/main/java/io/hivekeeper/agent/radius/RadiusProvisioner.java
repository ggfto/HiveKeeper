package io.hivekeeper.agent.radius;

import io.hivekeeper.core.model.PpskUserRecord;
import java.util.List;

/**
 * Renders the on-prem PPSK user set into the co-located RADIUS server's configuration (PPSK "Caminho B"
 * runtime). The agent's {@code FilePpskUserStore} invokes this after every mutation with the full set of
 * active users, so the RADIUS server always reflects the current state — provisioning is idempotent and
 * always derived from the complete set (never incremental), which keeps it crash-safe.
 *
 * <p>The default implementation ({@link FreeRadiusFilesProvisioner}) writes a FreeRADIUS {@code files}-module
 * authorize file and signals a reload. The exact Aerohive reply attributes for returning a PPSK + VLAN on
 * Access-Accept must be confirmed against a live AP before this leaves "untested" — see the PPSK-via-RADIUS
 * design doc.
 */
@FunctionalInterface
public interface RadiusProvisioner {

    /** Provision the RADIUS server from the complete current set of active PPSK users. */
    void provision(List<PpskUserRecord> activeUsers);
}
