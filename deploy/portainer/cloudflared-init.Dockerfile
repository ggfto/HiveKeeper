# Provisions the Cloudflare Tunnel through the API before the runner starts. Build from the repo ROOT.
#
# curl and jq are baked in rather than `apk add`ed at run time: an init container that reaches for the
# network before it can do its job turns a package-mirror hiccup into a failed deploy.
FROM alpine:3
RUN apk add --no-cache curl jq
COPY deploy/portainer/cloudflared/init.sh /init.sh
RUN chmod +x /init.sh
ENTRYPOINT ["/init.sh"]
