package io.hivekeeper.core.transport;

import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;

import java.io.File;
import java.io.IOException;
import java.security.PublicKey;

/**
 * Trust-on-first-use host-key verifier over a managed {@code known_hosts} file.
 *
 * <p>Extends sshj's {@link OpenSSHKnownHosts} and overrides the two decision hooks:
 * <ul>
 *   <li><b>unknown host</b> ({@link #hostKeyUnverifiableAction}) — record the key and accept (first trust);</li>
 *   <li><b>changed key</b> ({@link #hostKeyChangedAction}) — refuse, with a recoverable error message.</li>
 * </ul>
 * A host already present with a matching key is accepted by the base class without hitting either hook.
 *
 * <p>The verifier is pure enough to unit-test directly via {@link #verify(String, int, PublicKey)} — no live
 * SSH server is needed.
 */
@Slf4j
public class TofuKnownHostsVerifier extends OpenSSHKnownHosts {

    /**
     * @param knownHostsFile the managed known_hosts file; need not exist yet (it is created on first trust).
     *                       Its parent directory must exist so the first entry can be appended.
     */
    public TofuKnownHostsVerifier(File knownHostsFile) throws IOException {
        super(knownHostsFile);
    }

    @Override
    protected boolean hostKeyUnverifiableAction(String hostname, PublicKey key) {
        try {
            HostEntry entry = new HostEntry(null, hostname, KeyType.fromKey(key), key);
            entries.add(entry);
            write(entry);
            log.info("host key for {} trusted on first use ({}), recorded in {}",
                    hostname, entry.getFingerprint(), getFile());
            return true;
        } catch (IOException e) {
            log.error("could not record host key for {} in {}: {}", hostname, getFile(), e.getMessage());
            return false;
        }
    }

    @Override
    protected boolean hostKeyChangedAction(String hostname, PublicKey key) {
        log.error("host key for {} CHANGED — possible MITM, or the AP was reset/reflashed. "
                        + "If the change is legitimate, remove the line for this host from {} and reconnect to re-trust.",
                hostname, getFile());
        return false;
    }
}
