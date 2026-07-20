# The Keycloak realm bootstrapper, with its script baked in. Build from the repo ROOT.
#
# It runs inside the Keycloak image because it needs kcadm.sh. See postgres.Dockerfile for why this is a
# COPY and not a bind mount.
FROM quay.io/keycloak/keycloak:26.0
COPY deploy/keycloak/bootstrap.sh /bootstrap.sh
ENTRYPOINT ["/bin/bash", "/bootstrap.sh"]
