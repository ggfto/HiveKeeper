# The periodic pg_dump sidecar, with its script baked in. Build from the repo ROOT.
# See postgres.Dockerfile for why this is a COPY and not a bind mount.
FROM postgres:16-alpine
COPY deploy/postgres/backup.sh /backup.sh
ENTRYPOINT ["/bin/sh", "/backup.sh"]
