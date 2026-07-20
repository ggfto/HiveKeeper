# Postgres with the app-role init script baked in. Build from the repo ROOT.
#
# docker-compose.prod.yml bind-mounts this script; a Portainer Git stack cannot (the checkout never reaches
# the host's filesystem), so it is COPYed instead. The script itself stays where it was — one source of
# truth, shared with the non-Portainer deployment path.
FROM postgres:16-alpine
COPY deploy/postgres/10-init-app-role.sh /docker-entrypoint-initdb.d/10-init-app-role.sh
