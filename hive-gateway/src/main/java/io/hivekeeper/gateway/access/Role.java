package io.hivekeeper.gateway.access;

/**
 * The four roles, lowest to highest privilege. Each role includes every capability of the ones below it,
 * so a single {@code rank} comparison answers "is this role at least X?". Roles are granted at a scope
 * (see {@link Grant}); the effective role on a resource is the highest-ranked grant that covers it.
 */
public enum Role {
    VIEWER(0),    // read inventory, devices, backups, history
    OPERATOR(1),  // VIEWER + run operations (inventory/backup/config/reboot/restore)
    ADMIN(2),     // OPERATOR + manage structure (sites/groups/devices/agents) and members
    OWNER(3);     // ADMIN + organization-level (billing, delete org, manage owners)

    private final int rank;

    Role(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** True if this role confers at least the privilege of {@code other}. */
    public boolean atLeast(Role other) {
        return this.rank >= other.rank;
    }

    /** Parses a stored role string; throws on anything unrecognized rather than silently downgrading. */
    public static Role of(String value) {
        return Role.valueOf(value.trim().toUpperCase());
    }
}
