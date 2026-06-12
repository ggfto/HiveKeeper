-- Durable jobs for the gateway: a job survives a transient agent disconnect, and is redelivered when
-- the agent reconnects (the agent de-dupes by idempotency_key). Tenant-scoped with RLS, like operation_log.
create table job (
    job_id          text primary key,
    tenant_id       text not null references tenant (tenant_id),
    agent_id        text not null,
    idempotency_key text not null,
    type            text not null,
    command_json    text not null,
    status          text not null,                 -- PENDING, DISPATCHED, SUCCEEDED, FAILED
    result_json     text,
    error           text,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create index job_agent_status_idx on job (agent_id, status);

grant select, insert, update on job to hivekeeper_app;

alter table job enable row level security;
create policy job_tenant_isolation on job
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));
