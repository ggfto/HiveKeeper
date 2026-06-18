-- Runs once on first Postgres container init (as the superuser), BEFORE the gateway's Flyway runs.
-- Mirrors scripts/dev-postgres.ps1 and hive-gateway/src/test/resources/init-app-role.sql: create the
-- RESTRICTED application role the gateway connects as. NOSUPERUSER + NOBYPASSRLS is what makes Row-Level
-- Security genuinely enforced — the app role is subject to RLS; Flyway (as the 'postgres' admin) owns the
-- schema, policies, and grants.
CREATE ROLE hivekeeper_app LOGIN PASSWORD 'app' NOSUPERUSER NOBYPASSRLS;
GRANT CONNECT ON DATABASE hivekeeper TO hivekeeper_app;
GRANT USAGE ON SCHEMA public TO hivekeeper_app;
