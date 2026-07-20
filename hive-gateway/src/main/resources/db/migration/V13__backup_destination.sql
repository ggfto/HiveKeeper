-- Where an organization's config backups are pushed. One destination per tenant: the whole fleet writes to
-- one repository, and the per-device directories inside it already keep sites from colliding.
--
-- Unlike ppsk_user (V9), this table DOES hold the secret, encrypted at rest with HIVEKEEPER_CRYPTO_KEY, and
-- that is a deliberate trade rather than an oversight. A PPSK is minted once and pushed to one agent, so the
-- cloud can forget it immediately. A backup destination has to reach every agent in the org — including one
-- that was offline when it was set, and one enrolled next month — so the gateway must be able to re-seal it
-- on demand. The alternative was making an operator re-enter the token whenever an agent reconnected, which
-- turns a silent gap in backup coverage into the normal case.
--
-- The consequence, stated plainly: whoever holds both this database and HIVEKEEPER_CRYPTO_KEY can read the
-- token. Scope the token to a single backup repository and nothing else. The same trade already applies to
-- durable job payloads (SSID passphrases, hive passwords) in the `job` table.
create table backup_destination (
    tenant_id     text primary key references tenant (tenant_id),
    repo_url      text not null,
    branch        text not null default 'main',
    username      text not null default 'hivekeeper',
    token_enc     text not null,           -- gcm1: AES-256-GCM under HIVEKEEPER_CRYPTO_KEY
    updated_at    timestamptz not null default now(),
    updated_by    text
);

grant select, insert, update, delete on backup_destination to hivekeeper_app;

alter table backup_destination enable row level security;
create policy backup_destination_tenant_isolation on backup_destination
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));
