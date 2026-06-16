-- Admins register agents at runtime (generate an enrollment token), so the application role needs INSERT on
-- agent_enrollment. The baseline only granted SELECT, because enrollments were previously only ever seeded as
-- the Flyway superuser. agent_enrollment has no RLS (it is a cross-tenant auth-lookup table), so a plain grant
-- is enough; tenant isolation is enforced by the controller authorizing the caller's tenant scope.
grant insert on agent_enrollment to hivekeeper_app;
