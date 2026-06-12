-- Demo grant so agent-operation enforcement is legible in the browser: give the viewer user a viewer role
-- on the lab agent's site (in addition to its group grant). Then on lab-agent (bound to site-acme-default):
--   view  (viewer@site)    can read (inventory/backup) but not write (config/reboot);
--   op    (operator@site)  can read AND write;
--   owner (admin@org)      everything.
-- Dev/demo data only.
insert into role_grant (grant_id, membership_id, tenant_id, role, scope_type, scope_id) values
    ('g-view-site', 'mb-view', 'acme', 'viewer', 'site', 'site-acme-default');
