-- Deleting an agent frees its id for a clean re-install, which means the enrollment its certificate CN
-- resolves to has to go too — otherwise the identity disappears from the fleet while the credential that
-- admits it at the handshake lives on, and the deleted agent keeps connecting. The baseline granted SELECT
-- (V1), INSERT (V8) and UPDATE (V11, for one-time consumption and revocation); DELETE completes the set.
--
-- agent_enrollment has no RLS (it is the cross-tenant auth-lookup table resolved before a tenant context
-- exists), so the grant is unqualified and the tenant wall is the caller's: PostgresFleetService.deleteAgent
-- scopes its DELETE by tenant_id explicitly, and the controller authorizes admin on the agent's scope first.
grant delete on agent_enrollment to hivekeeper_app;
