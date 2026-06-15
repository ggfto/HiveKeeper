-- HiveKeeper gateway schema. Run by Flyway as an admin role (the table owner). The application
-- connects as the restricted role 'hivekeeper_app' (NOSUPERUSER, NOBYPASSRLS), so the RLS policy on
-- operation_log is genuinely enforced by Postgres, not by application code.

-- Auth-lookup tables: queried cross-tenant (resolve an API key / token), so NO row-level security.
create table tenant (
    tenant_id        text primary key,
    name             text not null,
    operator_api_key text not null unique
);

create table agent_enrollment (
    token     text primary key,
    agent_id  text not null unique,
    tenant_id text not null references tenant (tenant_id)
);

-- Tenant-scoped data: an audit log of operations dispatched to agents. RLS-protected.
create table operation_log (
    id         bigserial primary key,
    tenant_id  text not null references tenant (tenant_id),
    agent_id   text not null,
    op_type    text not null,
    host       text,
    summary    text,
    created_at timestamptz not null default now()
);

-- No demo data here: tenants + the enrolled agent are seeded only under the 'demo' profile
-- (classpath:db/seed-dev). Baseline migrations are schema-only so a production database ships no
-- known-public credentials.

-- Grant the restricted app role exactly what it needs.
grant select on tenant to hivekeeper_app;
grant select on agent_enrollment to hivekeeper_app;
grant select, insert on operation_log to hivekeeper_app;
grant usage, select on sequence operation_log_id_seq to hivekeeper_app;

-- Row-Level Security: a row is visible/insertable only within its tenant context, set per-transaction
-- via set_config('app.current_tenant', '<tenant>', true). Unset context -> NULL -> no rows.
alter table operation_log enable row level security;
create policy operation_log_tenant_isolation on operation_log
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));
