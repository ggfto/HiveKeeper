-- DEV / DEMO SEED ONLY.
-- Applied only under the 'demo' profile, which adds this location to spring.flyway.locations. These are
-- PUBLIC, well-known credentials (acme-key / globex-key / enroll-lab-agent) and demo identities — they must
-- NEVER be applied to a production database. Versioned (V900, after all baseline migrations) so it runs once.

-- Demo tenants + the lab agent enrollment (mirrors InMemoryTenantStore's demo seed).
insert into tenant (tenant_id, name, operator_api_key) values
    ('acme', 'Acme Corp', 'acme-key'),
    ('globex', 'Globex', 'globex-key');
insert into agent_enrollment (token, agent_id, tenant_id) values
    ('enroll-lab-agent', 'lab-agent', 'acme');

-- A Default site + group for the demo tenant, and bind the lab agent to that site.
insert into site (site_id, tenant_id, name) values
    ('site-acme-default', 'acme', 'Default');
insert into fleet_group (group_id, tenant_id, site_id, name) values
    ('grp-acme-default', 'acme', 'site-acme-default', 'Default');
update agent_enrollment set site_id = 'site-acme-default' where agent_id = 'lab-agent';

-- Demo users exercising scoped roles (owner@org, operator@site, viewer@group + viewer@site) so the resolver
-- can be tried via the API.
insert into app_user (user_id, oidc_issuer, oidc_subject, email, name) values
    ('usr-owner', 'dev', 'owner', 'owner@acme.test', 'Olivia Owner'),
    ('usr-op',    'dev', 'op',    'op@acme.test',    'Otto Operator'),
    ('usr-view',  'dev', 'view',  'view@acme.test',  'Vera Viewer');
insert into membership (membership_id, user_id, tenant_id) values
    ('mb-owner', 'usr-owner', 'acme'),
    ('mb-op',    'usr-op',    'acme'),
    ('mb-view',  'usr-view',  'acme');
insert into role_grant (grant_id, membership_id, tenant_id, role, scope_type, scope_id) values
    ('g-owner',     'mb-owner', 'acme', 'owner',    'org',   null),
    ('g-op',        'mb-op',    'acme', 'operator', 'site',  'site-acme-default'),
    ('g-view',      'mb-view',  'acme', 'viewer',   'group', 'grp-acme-default'),
    ('g-view-site', 'mb-view',  'acme', 'viewer',   'site',  'site-acme-default');

-- Point the demo users at the dev Keycloak realm (fixed subjects declared in scripts/hivekeeper-realm.json) so
-- a real login resolves to these seeded users that already hold scoped grants.
update app_user set oidc_issuer = 'http://localhost:8081/realms/hivekeeper',
    oidc_subject = '11111111-1111-1111-1111-111111111111' where user_id = 'usr-owner';
update app_user set oidc_issuer = 'http://localhost:8081/realms/hivekeeper',
    oidc_subject = '22222222-2222-2222-2222-222222222222' where user_id = 'usr-op';
update app_user set oidc_issuer = 'http://localhost:8081/realms/hivekeeper',
    oidc_subject = '33333333-3333-3333-3333-333333333333' where user_id = 'usr-view';

-- Give the owner a second organization so the org switcher has something to switch to.
insert into membership (membership_id, user_id, tenant_id) values ('mb-owner-globex', 'usr-owner', 'globex');
insert into role_grant (grant_id, membership_id, tenant_id, role, scope_type, scope_id) values
    ('g-owner-globex', 'mb-owner-globex', 'globex', 'viewer', 'org', null);
