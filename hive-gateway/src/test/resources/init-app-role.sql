-- Runs once on container start (as the superuser) BEFORE Flyway, mirroring scripts/dev-postgres.ps1: create
-- the RESTRICTED application role the gateway connects as. NOSUPERUSER + NOBYPASSRLS is what makes the RLS
-- integration tests meaningful — the app role is genuinely subject to row-level security.
CREATE ROLE hivekeeper_app LOGIN PASSWORD 'app' NOSUPERUSER NOBYPASSRLS;
GRANT CONNECT ON DATABASE hivekeeper TO hivekeeper_app;
GRANT USAGE ON SCHEMA public TO hivekeeper_app;
