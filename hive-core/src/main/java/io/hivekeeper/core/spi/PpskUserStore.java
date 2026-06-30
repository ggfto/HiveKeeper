package io.hivekeeper.core.spi;

import io.hivekeeper.core.model.PpskUserRecord;
import java.util.List;

/**
 * The on-prem store of Private-PSK users for PPSK "Caminho B" — the seam that lets HiveKeeper own the key
 * lifecycle without depending on AP-side key grammar (HiveOS has none over SSH). The on-prem agent
 * implements this: it persists each user↔PSK mapping locally (encrypted at rest) and provisions the
 * co-located RADIUS server from it. The cloud never calls this directly — it sends a
 * {@link io.hivekeeper.core.api.Command.ManagePpskUser} command and the on-prem engine performs the write.
 */
public interface PpskUserStore {

    /** Creates or replaces (rotate) the record for its {@code securityObject}+{@code username} key. */
    void put(PpskUserRecord record);

    /** Removes (revoke) the user; returns whether a record existed. */
    boolean remove(String securityObject, String username);

    /** All active records, for provisioning the RADIUS server and for metadata listing. */
    List<PpskUserRecord> list();
}
