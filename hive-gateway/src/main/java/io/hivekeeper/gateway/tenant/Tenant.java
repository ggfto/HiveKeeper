package io.hivekeeper.gateway.tenant;

/** A tenant (customer/org). {@code operatorApiKey} authenticates a human operator for the REST API in
 *  this v1; production swaps it for OIDC, with the same tenant scoping underneath. */
public record Tenant(String tenantId, String name, String operatorApiKey) {
}
