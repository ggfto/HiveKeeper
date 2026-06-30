-- PPSK "Caminho B" — admin-minted Private-PSK users. The gateway owns the key LIFECYCLE and stores only
-- metadata + a reference (psk_ref): the usable PSK is generated, sealed to the on-prem agent, and held in
-- the agent's RADIUS store on the LAN — it is NEVER persisted here, not even encrypted. Tenant-scoped under
-- the same row-level-security wall as the rest of the fleet.
create table ppsk_user (
    ppsk_user_id      text primary key,
    tenant_id         text not null references tenant (tenant_id),
    agent_id          text not null,
    security_object   text not null,
    user_group        text,
    username          text not null,
    psk_ref           text not null,          -- reference only; the cloud never stores the usable key
    user_profile_attr integer,
    vlan_id           integer,
    schedule_name     text,
    mac_bindings      text,                    -- comma-separated bound client MACs (optional)
    status            text not null default 'active' check (status in ('active', 'revoked')),
    created_at        timestamptz not null default now(),
    rotated_at        timestamptz
);

-- One ACTIVE user per (agent, security-object, username); revoked rows are kept for audit and do not block
-- re-creating the same username later.
create unique index ppsk_user_active_uq
    on ppsk_user (tenant_id, agent_id, security_object, username) where status = 'active';
create index ppsk_user_agent_idx on ppsk_user (tenant_id, agent_id);

grant select, insert, update, delete on ppsk_user to hivekeeper_app;

alter table ppsk_user enable row level security;
create policy ppsk_user_tenant_isolation on ppsk_user
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));
