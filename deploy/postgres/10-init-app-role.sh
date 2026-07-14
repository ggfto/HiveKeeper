#!/bin/bash
# Runs once on first Postgres container init (as the superuser), BEFORE the gateway's Flyway runs.
# Creates the RESTRICTED application role the gateway connects as. NOSUPERUSER + NOBYPASSRLS is what makes
# Row-Level Security genuinely enforced — the app role is subject to RLS, while Flyway (as the 'postgres'
# admin) owns the schema, policies and grants.
#
# A shell script and not plain .sql because the password has to come from the environment: psql cannot read
# env vars, so a .sql file can only ever hardcode one — which in production means every deployment shares the
# same database password.
#
# HIVEKEEPER_DB_PASSWORD defaults to 'app' for the zero-config development stack. Production sets a generated
# one (scripts/init-secrets.sh) and it must match what the gateway connects with.
set -e

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     -v app_password="${HIVEKEEPER_DB_PASSWORD:-app}" \
     -v db_name="$POSTGRES_DB" <<-'EOSQL'
	-- :'app_password' — psql quotes and escapes it, so a password containing a quote cannot break out.
	CREATE ROLE hivekeeper_app LOGIN PASSWORD :'app_password' NOSUPERUSER NOBYPASSRLS;
	GRANT CONNECT ON DATABASE :"db_name" TO hivekeeper_app;
	GRANT USAGE ON SCHEMA public TO hivekeeper_app;
EOSQL
