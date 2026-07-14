#!/usr/bin/env bash
# Build and push the HiveKeeper container images to a registry, tagged with the release version and `latest`.
# Invoked by semantic-release (see .releaserc.json publishCmd) once a release is cut, so it only ever runs on
# an actual version bump. Assumes the caller is already logged in to the registry (the release workflow does
# the ghcr login) and that gradle.properties already holds the release version (prepareCmd updated it).
#
#   scripts/release-images.sh <version>
#   REGISTRY=ghcr.io/ggfto scripts/release-images.sh 0.2.0
set -euo pipefail

VERSION="${1:?usage: release-images.sh <version>}"
REGISTRY="${REGISTRY:-ghcr.io/ggfto}"

# module name -> image name suffix. Each module has its own Dockerfile that builds from the repo root.
# `web` is the console: without it a deployment has an API and no user interface.
for module in gateway agent server web; do
  image="${REGISTRY}/hivekeeper-${module}"
  echo ">> building ${image}:${VERSION}"
  docker build -f "hive-${module}/Dockerfile" \
    -t "${image}:${VERSION}" \
    -t "${image}:latest" \
    .
  docker push "${image}:${VERSION}"
  docker push "${image}:latest"
done

echo ">> published gateway/agent/server/web images at ${VERSION}"
