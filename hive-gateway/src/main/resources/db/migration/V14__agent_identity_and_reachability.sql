-- First-class agents + explicit device<->agent reachability. Before this migration an "agent" existed only
-- as an `agent_enrollment` row (PK = a rotating token) and a device pinned to at most one agent via the
-- scalar `device.agent_id`. That single pin could not express the reality that two or more agents reach the
-- same access point (an active/standby pair, a load split, distinct network vantage points, or a migration).
--
-- Two axes still meet at the device, but the PHYSICAL one is now explicit: the LOGICAL tree (site -> group)
-- is unchanged and stays on `device.site_id`; the PHYSICAL reach becomes the many-to-many `device_agent`
-- (which agents can drive this AP), decoupled from the site. Selecting the ONE agent that runs an unattended
-- job is deterministic-by-id over that reachable set (see SitePrimary) — so a device reachable by several
-- agents is dispatched to exactly one, never twice, without a single owning pin.
--
-- ORDER IS LOAD-BEARING: `agent` must exist and be backfilled before `device_agent` references it, and
-- `device_agent` must be seeded from the old pins before `device.agent_id` is dropped.

-- ---------------------------------------------------------------------------
-- 1. The durable agent identity — a tenant-scoped fleet resource (unlike `agent_enrollment`, which is the
-- RLS-free credential/lifecycle record resolved cross-tenant on the auth path). Mirrors `site`'s composite
-- unique so join tables can prove same-tenant linkage via a composite FK.
-- ---------------------------------------------------------------------------
create table agent (
    agent_id   text primary key,
    tenant_id  text not null references tenant (tenant_id),
    name       text not null,
    site_id    text,
    created_at timestamptz not null default now(),
    last_seen  timestamptz,
    unique (agent_id, tenant_id),
    foreign key (site_id, tenant_id) references site (site_id, tenant_id)
);
create index agent_site_idx on agent (site_id);

grant select, insert, update, delete on agent to hivekeeper_app;

alter table agent enable row level security;
create policy agent_tenant_isolation on agent
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));

-- Backfill identity from every existing enrollment (runs as the Flyway owner, so RLS is bypassed). Name
-- defaults to the agent id and the site is carried over; the app overwrites the name later if it grows one.
-- agent_enrollment.agent_id is already unique, so this is one row per agent. NOTE: we deliberately do NOT add
-- an FK from agent_enrollment.agent_id to agent — the demo seed (V900) inserts an enrollment before its agent
-- row exists (V901), and the app always creates the agent alongside the enrollment, so the FK would buy an
-- ordering hazard for no invariant we rely on.
insert into agent (agent_id, tenant_id, name, site_id)
select agent_id, tenant_id, agent_id, site_id from agent_enrollment;

-- ---------------------------------------------------------------------------
-- 2. The many-to-many reachability join (which agents can drive which device). Modeled exactly on
-- `device_group`: tenant_id denormalized for the shared RLS policy, and composite FKs force the device AND
-- the agent to share this row's tenant so reachability can never cross the tenant wall.
-- ---------------------------------------------------------------------------
create table device_agent (
    tenant_id      text not null references tenant (tenant_id),
    device_id      text not null,
    agent_id       text not null,
    added_at       timestamptz not null default now(),
    last_reachable timestamptz,
    primary key (device_id, agent_id),
    foreign key (device_id, tenant_id) references device (device_id, tenant_id) on delete cascade,
    foreign key (agent_id, tenant_id) references agent (agent_id, tenant_id) on delete cascade
);
create index device_agent_agent_idx on device_agent (agent_id);

grant select, insert, update, delete on device_agent to hivekeeper_app;

alter table device_agent enable row level security;
create policy device_agent_tenant_isolation on device_agent
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));

-- Seed reachability from the old scalar pins: every device that was pinned to an agent becomes reachable by
-- that agent. Guarded so a dangling pin (an agent_id with no backfilled `agent` row) is skipped rather than
-- violating the composite FK.
insert into device_agent (tenant_id, device_id, agent_id)
select d.tenant_id, d.device_id, d.agent_id
from device d
where d.agent_id is not null
  and exists (select 1 from agent a where a.agent_id = d.agent_id and a.tenant_id = d.tenant_id);

-- ---------------------------------------------------------------------------
-- 3. Drop the scalar pin — reachability now lives entirely in device_agent. Must be last, after the seed.
-- ---------------------------------------------------------------------------
alter table device drop column agent_id;
