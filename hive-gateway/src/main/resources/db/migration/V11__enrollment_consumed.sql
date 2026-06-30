-- Automated agent enrollment (token -> CSR -> signed cert). A one-time enrollment token may be exchanged for
-- exactly one client certificate; once exchanged it is marked consumed and can never mint another. Consumption
-- is enforced atomically (update ... where consumed_at is null), so two concurrent bootstrap requests cannot
-- both win the same token.
alter table agent_enrollment add column consumed_at timestamptz;

-- The app role could already SELECT (V1) and INSERT (V8) enrollments; consuming a token needs UPDATE too.
-- agent_enrollment is a cross-tenant auth-lookup table with no RLS, so a plain grant is sufficient.
grant update on agent_enrollment to hivekeeper_app;
