-- DEV / DEMO SEED ONLY (applied only under the 'demo' profile, after V900). PUBLIC, well-known identities —
-- must NEVER reach a production database.
--
-- V14 made the agent a first-class entity and reachability an explicit device<->agent join. The baseline
-- backfill in V14 ran BEFORE V900 seeded the lab agent's enrollment, so the demo needs its own `agent` row —
-- without it, adopting a demo AP (which inserts a device_agent row) would violate the device_agent -> agent
-- composite FK. We also add a second agent on the same site so the console shows an active/standby pair and
-- the "two agents reach one AP" behaviour can be exercised end to end.

-- The lab agent's durable identity (its enrollment + site were seeded in V900).
insert into agent (agent_id, tenant_id, name, site_id) values
    ('lab-agent', 'acme', 'lab-agent', 'site-acme-default');

-- A second agent on the same site: the standby half of the active/standby pair. lab-agent sorts first, so it
-- is the deterministic primary; lab-agent-02 takes over when it is down.
insert into agent_enrollment (token, agent_id, tenant_id, site_id) values
    ('enroll-lab-agent-02', 'lab-agent-02', 'acme', 'site-acme-default');
insert into agent (agent_id, tenant_id, name, site_id) values
    ('lab-agent-02', 'acme', 'lab-agent-02', 'site-acme-default');
