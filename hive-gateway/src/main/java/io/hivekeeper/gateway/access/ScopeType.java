package io.hivekeeper.gateway.access;

/** The level a {@link Grant} applies at. ORG covers the whole organization; SITE covers one site and all
 *  its groups/devices; GROUP covers the devices in one group. */
public enum ScopeType {
    ORG,
    SITE,
    GROUP;

    public static ScopeType of(String value) {
        return ScopeType.valueOf(value.trim().toUpperCase());
    }
}
