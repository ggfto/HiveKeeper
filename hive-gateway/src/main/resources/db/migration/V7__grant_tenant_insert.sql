-- First-run setup creates the first tenant through the restricted application role, so it needs INSERT on
-- tenant. The baseline only granted SELECT, because tenants were previously only ever seeded as the Flyway
-- superuser. tenant has no RLS (it is a cross-tenant auth-lookup table), so a plain grant is enough.
grant insert on tenant to hivekeeper_app;
