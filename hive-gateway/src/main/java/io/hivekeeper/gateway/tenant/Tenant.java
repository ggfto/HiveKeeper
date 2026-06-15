package io.hivekeeper.gateway.tenant;

/** A tenant (customer/org). {@code operatorApiKey} authenticates a service caller via {@code X-Tenant-Key};
 *  {@code operatorRole} is the role that key confers (a lowercase role name, default {@code owner}) so a key
 *  can be issued less than full rights. Human operators authenticate via OIDC instead, with the same tenant
 *  scoping underneath. */
public record Tenant(String tenantId, String name, String operatorApiKey, String operatorRole) {
}
