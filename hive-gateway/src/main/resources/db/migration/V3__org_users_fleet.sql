-- Organization structure + identity + fleet. The organization IS the tenant (the `tenant` table from
-- V1); the column stays `tenant_id` everywhere for a uniform RLS pattern, even though the domain calls it
-- "organization". Two axes meet at the device: the LOGICAL tree (site -> group) for how humans organize
-- and permission, and the PHYSICAL reach (a site's agent on the LAN). A device is pinned to one site and
-- may belong to many groups (groups + tags). Authorization (who can do what, by scope) is application
-- logic over role_grant; RLS remains the hard wall BETWEEN organizations.

-- ---------------------------------------------------------------------------
-- Identity. A user is GLOBAL (spans organizations via membership), so app_user has NO row-level security
-- and is looked up by its OIDC identity at login — the same "auth-lookup, queried cross-tenant" rationale
-- as the tenant / agent_enrollment tables.
-- ---------------------------------------------------------------------------
create table app_user (
    user_id      text primary key,
    oidc_issuer  text not null,
    oidc_subject text not null,
    email        text,
    name         text,
    created_at   timestamptz not null default now(),
    unique (oidc_issuer, oidc_subject)
);

-- A user's membership in an organization (multi-org: one account, many orgs).
create table membership (
    membership_id text primary key,
    user_id       text not null references app_user (user_id),
    tenant_id     text not null references tenant (tenant_id),
    status        text not null default 'active' check (status in ('active', 'suspended', 'invited')),
    created_at    timestamptz not null default now(),
    unique (user_id, tenant_id)
);

-- A scoped role grant: the actual permission. role in owner|admin|operator|viewer; scope_type in
-- org|site|group with scope_id naming the site/group (null for org-wide). tenant_id is denormalized so the
-- same RLS policy guards it. Grants hang off a membership, so removing a member removes their access.
create table role_grant (
    grant_id      text primary key,
    membership_id text not null references membership (membership_id) on delete cascade,
    tenant_id     text not null references tenant (tenant_id),
    role          text not null check (role in ('owner', 'admin', 'operator', 'viewer')),
    scope_type    text not null check (scope_type in ('org', 'site', 'group')),
    scope_id      text,
    created_at    timestamptz not null default now()
);
create index role_grant_membership_idx on role_grant (membership_id);

-- ---------------------------------------------------------------------------
-- Fleet structure. All tenant-scoped (RLS by tenant_id).
-- ---------------------------------------------------------------------------
create table site (
    site_id    text primary key,
    tenant_id  text not null references tenant (tenant_id),
    name       text not null,
    created_at timestamptz not null default now(),
    unique (tenant_id, name),
    -- composite-unique target so child tables can reference (site_id, tenant_id) and the FK itself proves
    -- a site is only ever pinned within its own tenant (FK checks bypass RLS, so single-column FKs would
    -- let one org reference another org's site).
    unique (site_id, tenant_id)
);

-- A logical group of devices. site_id set => a group within that site (the common tree); site_id null =>
-- a cross-site tag. A device may belong to many groups.
create table fleet_group (
    group_id   text primary key,
    tenant_id  text not null references tenant (tenant_id),
    site_id    text,
    name       text not null,
    created_at timestamptz not null default now(),
    unique (group_id, tenant_id),
    -- same-tenant pinning enforced by the composite FK (null site_id = cross-site tag, FK skipped).
    foreign key (site_id, tenant_id) references site (site_id, tenant_id)
);
create index fleet_group_site_idx on fleet_group (site_id);

-- A managed device. Identity is the AP serial (stable across DHCP), unique within an org. Reached via the
-- agent on its site; the AP credential lives ON the agent — cred_ref is only a pointer the agent resolves.
create table device (
    device_id      text primary key,
    tenant_id      text not null references tenant (tenant_id),
    site_id        text,
    agent_id       text,
    serial         text not null,
    model          text,
    label          text,
    mgmt_ip        text,
    cred_ref       text,
    last_seen      timestamptz,
    last_inventory text,
    created_at     timestamptz not null default now(),
    unique (tenant_id, serial),
    unique (device_id, tenant_id),
    foreign key (site_id, tenant_id) references site (site_id, tenant_id)
);
create index device_site_idx on device (site_id);

-- The many-to-many device <-> group (groups + tags). tenant_id denormalized for the shared RLS policy.
create table device_group (
    tenant_id text not null references tenant (tenant_id),
    device_id text not null,
    group_id  text not null,
    primary key (device_id, group_id),
    -- composite FKs force the device AND the group to share this row's tenant_id (which the RLS with-check
    -- pins to the current tenant), so one org can never tag across the tenant wall.
    foreign key (device_id, tenant_id) references device (device_id, tenant_id) on delete cascade,
    foreign key (group_id, tenant_id) references fleet_group (group_id, tenant_id) on delete cascade
);

-- An agent runs on a site's LAN (physical reach). Bind the existing enrollment record to a site.
alter table agent_enrollment add column site_id text references site (site_id);

-- ---------------------------------------------------------------------------
-- Grants for the restricted application role.
-- ---------------------------------------------------------------------------
grant select, insert, update on app_user to hivekeeper_app;
grant select, insert, update, delete on membership to hivekeeper_app;
grant select, insert, update, delete on role_grant to hivekeeper_app;
grant select, insert, update, delete on site to hivekeeper_app;
grant select, insert, update, delete on fleet_group to hivekeeper_app;
grant select, insert, update, delete on device to hivekeeper_app;
grant select, insert, update, delete on device_group to hivekeeper_app;

-- ---------------------------------------------------------------------------
-- Row-Level Security: every tenant-scoped table is visible/writable only inside its tenant context, set
-- per transaction via set_config('app.current_tenant', '<tenant>', true). app_user stays global.
-- ---------------------------------------------------------------------------
alter table membership enable row level security;
create policy membership_tenant_isolation on membership
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));

alter table role_grant enable row level security;
create policy role_grant_tenant_isolation on role_grant
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));

alter table site enable row level security;
create policy site_tenant_isolation on site
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));

alter table fleet_group enable row level security;
create policy fleet_group_tenant_isolation on fleet_group
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));

alter table device enable row level security;
create policy device_tenant_isolation on device
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));

alter table device_group enable row level security;
create policy device_group_tenant_isolation on device_group
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));

-- The demo site/group/users/memberships/grants that exercise scoped roles live in the dev-only seed
-- (classpath:db/seed-dev, applied only under the 'demo' profile). Baseline migrations are schema-only.
