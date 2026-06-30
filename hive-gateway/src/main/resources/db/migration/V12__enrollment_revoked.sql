-- Certificate revocation + re-enrollment (enrollment slice 2). An operator can revoke an agent (decommission
-- or compromise) so the gateway refuses its mTLS handshake AND its certificate renewals, and can re-enroll it
-- with a fresh one-time token (clearing the revoked/consumed marks) to provision a replacement.
--
-- Revocation is by agent identity (the cert CN = agent_id is server-assigned and stable across renewals), so a
-- single mark covers every certificate the agent could present. The gateway is the sole relying party for these
-- client certs, so it enforces revocation directly at the handshake — no CRL/OCSP distribution is needed.
alter table agent_enrollment add column revoked_at     timestamptz;
alter table agent_enrollment add column revoked_reason text;

-- The app role already has SELECT (V1), INSERT (V8) and UPDATE (V11) on agent_enrollment; revoke and re-enroll
-- are UPDATEs (re-enroll rewrites the token PK, which UPDATE covers), so no new grant is required.
