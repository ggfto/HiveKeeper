-- Fleet alerting delivery: notification channels, per-tenant thresholds, and the firing-alert state the
-- background poller uses to deliver on onset/resolution without re-notifying every scan. All tenant-scoped
-- under the same row-level-security wall as the rest of the fleet.

-- Where to deliver alerts. `target` is a webhook URL or an email address; `min_severity` gates which alerts
-- this channel receives (critical < warning < info, so 'warning' means warning+critical).
create table alert_channel (
    channel_id   text primary key,
    tenant_id    text not null references tenant (tenant_id),
    type         text not null check (type in ('webhook', 'email')),
    target       text not null,
    min_severity text not null default 'warning' check (min_severity in ('critical', 'warning', 'info')),
    enabled      boolean not null default true,
    created_at   timestamptz not null default now(),
    unique (tenant_id, type, target)
);
create index alert_channel_tenant_idx on alert_channel (tenant_id) where enabled;
grant select, insert, update, delete on alert_channel to hivekeeper_app;
alter table alert_channel enable row level security;
create policy alert_channel_tenant_isolation on alert_channel
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));

-- Per-tenant alert settings (the poller's thresholds + an on/off switch). One row per tenant.
create table alert_settings (
    tenant_id    text primary key references tenant (tenant_id),
    max_stations integer not null default 30,
    poll_enabled boolean not null default true,
    updated_at   timestamptz not null default now()
);
grant select, insert, update, delete on alert_settings to hivekeeper_app;
alter table alert_settings enable row level security;
create policy alert_settings_tenant_isolation on alert_settings
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));

-- The set of CURRENTLY-FIRING alerts (one row per tenant/device/rule). The poller diffs each scan against
-- this table: a rule not present here is a new firing (deliver + insert); a row absent from the new scan has
-- resolved (deliver + delete). This is what stops a re-notification storm on every poll.
create table fleet_alert (
    tenant_id  text not null references tenant (tenant_id),
    device_id  text not null,
    alert_id   text not null,
    agent_id   text,
    severity   text not null,
    message    text,
    first_seen timestamptz not null default now(),
    last_seen  timestamptz not null default now(),
    primary key (tenant_id, device_id, alert_id)
);
create index fleet_alert_tenant_idx on fleet_alert (tenant_id);
grant select, insert, update, delete on fleet_alert to hivekeeper_app;
alter table fleet_alert enable row level security;
create policy fleet_alert_tenant_isolation on fleet_alert
    using (tenant_id = current_setting('app.current_tenant', true))
    with check (tenant_id = current_setting('app.current_tenant', true));
