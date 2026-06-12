-- OIDC identity support: a safe cross-organization membership lookup, plus re-pointing the demo users at
-- the dev Keycloak so the login flow can be exercised end to end.

-- The org switcher needs "which organizations does this user belong to" — a per-USER query that spans
-- tenants. membership is RLS-scoped by tenant (a query only ever sees one org's rows under a tenant
-- context), so this cannot be answered by the app role directly. This SECURITY DEFINER function runs as its
-- OWNER — currently the superuser Flyway migrates as — bypassing RLS, but returns ONLY the rows for the
-- passed user_id, so it exposes a single user's own memberships without leaking any org's full member list.
-- The caller (MeController) only ever passes the authenticated user's id. search_path is pinned to defeat
-- search-path hijacking, the standard SECURITY DEFINER hardening. The body is a single static SQL SELECT
-- (no dynamic SQL), so there is no injection surface today.
-- TODO(hardening): transfer ownership to a dedicated NOLOGIN NOSUPERUSER BYPASSRLS role so a future edit
-- cannot execute with superuser rights (deferred — a cluster-global role is an ops/deployment concern).
create function user_memberships(p_user_id text)
    returns table (tenant_id text, tenant_name text, status text)
    language sql
    security definer
    set search_path = public
as $$
    select m.tenant_id, t.name, m.status
    from membership m
    join tenant t on m.tenant_id = t.tenant_id
    where m.user_id = p_user_id
$$;

revoke all on function user_memberships(text) from public;
grant execute on function user_memberships(text) to hivekeeper_app;

-- Point the demo users at the dev Keycloak realm (fixed user ids declared in scripts/hivekeeper-realm.json)
-- so a real login resolves to the seeded users that already hold scoped grants. Dev/demo data only.
update app_user set oidc_issuer = 'http://localhost:8081/realms/hivekeeper',
    oidc_subject = '11111111-1111-1111-1111-111111111111' where user_id = 'usr-owner';
update app_user set oidc_issuer = 'http://localhost:8081/realms/hivekeeper',
    oidc_subject = '22222222-2222-2222-2222-222222222222' where user_id = 'usr-op';
update app_user set oidc_issuer = 'http://localhost:8081/realms/hivekeeper',
    oidc_subject = '33333333-3333-3333-3333-333333333333' where user_id = 'usr-view';

-- Give the owner a second organization so the switcher has something to switch to.
insert into membership (membership_id, user_id, tenant_id) values ('mb-owner-globex', 'usr-owner', 'globex');
insert into role_grant (grant_id, membership_id, tenant_id, role, scope_type, scope_id) values
    ('g-owner-globex', 'mb-owner-globex', 'globex', 'viewer', 'org', null);
