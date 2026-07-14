#!/bin/sh
# Periodic logical backups of BOTH databases HiveKeeper depends on, into the pg-backups volume.
#
# Both, because either one alone is useless:
#   hivekeeper  the fleet, the org/site/group model, role grants, the audit log, durable jobs, PPSK metadata.
#   keycloak    the identities those role grants point AT. Restore the gateway's database alone and every
#               membership refers to a user id that no longer exists — nobody can sign in, including you.
#
# What this does NOT back up, and you must:
#   * .env.prod          HIVEKEEPER_CRYPTO_KEY decrypts the secrets inside these dumps. Restoring the dump
#                        without the key gives you ciphertext you can never open again.
#   * secrets/pki/       the CA that signs agent certificates. Without it the whole fleet must re-enroll.
#   * the AGENT's volume — it holds the credential vault, the pinned SSH host keys, and the git history of
#                        every device config, which is your rollback path. It lives on the agent's machine.
#
# Runs as its own container in docker-compose.prod.yml. A sleep loop, not cron: one less daemon, and the
# container's restart policy is the supervision.
set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
INTERVAL_HOURS="${HIVEKEEPER_BACKUP_INTERVAL_HOURS:-24}"
RETENTION_DAYS="${HIVEKEEPER_BACKUP_RETENTION_DAYS:-14}"

mkdir -p "$BACKUP_DIR"

dump() {
  host="$1"; user="$2"; db="$3"; password="$4"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  out="${BACKUP_DIR}/${db}-${stamp}.sql.gz"

  # To a temporary name first, renamed only on success: a backup half-written when the container was killed
  # must never be mistaken for a good one. That is the failure you only find out about while restoring.
  if PGPASSWORD="$password" pg_dump -h "$host" -U "$user" -d "$db" | gzip > "${out}.part"; then
    mv "${out}.part" "$out"
    echo "$(date -u +%FT%TZ) backed up ${db} -> $(basename "$out") ($(wc -c < "$out") bytes)"
  else
    rm -f "${out}.part"
    echo "$(date -u +%FT%TZ) BACKUP FAILED for ${db}" >&2
    return 1
  fi
}

while true; do
  # Neither failure stops the other: a broken Keycloak dump must not also cost you the fleet's.
  dump postgres postgres hivekeeper "${HIVEKEEPER_DB_ADMIN_PASSWORD}" || true
  dump keycloak-db keycloak keycloak "${KEYCLOAK_DB_PASSWORD}" || true

  # Prune, but only ever completed dumps (*.sql.gz, never *.part).
  find "$BACKUP_DIR" -name '*.sql.gz' -type f -mtime "+${RETENTION_DAYS}" -print -delete \
    | sed 's/^/pruned /' || true

  sleep "$((INTERVAL_HOURS * 3600))"
done
