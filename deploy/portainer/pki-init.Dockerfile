# Mints the private PKI into the `pki` volume on first boot. Build from the repo ROOT.
#
# A JRE image because all it needs is keytool — the same tool scripts/init-secrets.sh drives from a
# container for the non-Portainer path, so the two produce identical stores.
FROM eclipse-temurin:21-jre
COPY deploy/portainer/gen-pki.sh /gen-pki.sh
RUN chmod +x /gen-pki.sh
ENTRYPOINT ["/gen-pki.sh"]
