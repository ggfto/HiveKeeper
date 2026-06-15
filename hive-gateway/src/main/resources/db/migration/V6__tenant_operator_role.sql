-- The role an X-Tenant-Key confers. Defaults to 'owner' (the historical behaviour: a key had full rights), but
-- a key can now be issued a lower role (viewer/operator/admin) so automation gets least privilege.
alter table tenant add column operator_role text not null default 'owner';
