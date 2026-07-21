#!/usr/bin/env bash
# Build and push the HiveKeeper container images to a registry, MULTI-ARCH (linux/amd64 + linux/arm64),
# tagged with the release version and `latest`. Invoked by semantic-release (see .releaserc.json publishCmd)
# once a release is cut, so it only ever runs on an actual version bump. Assumes the caller is already logged
# in to the registry, that `docker buildx` and the QEMU emulators are set up (the release workflow does the
# ghcr login + setup-qemu/setup-buildx), and that gradle.properties already holds the release version.
#
# Multi-arch matters: hosts are not all amd64 (an Oracle Cloud Ampere box, a Raspberry Pi, an Apple-silicon
# dev machine are all arm64). A single-arch amd64 image runs on arm64 only under QEMU emulation, which for a
# JVM service is unstable — it segfaults. So we publish a manifest list with a native image per arch.
#
# The build stage builds ONCE on the native runner (`--platform=$BUILDPLATFORM` in each Dockerfile): its
# output — jars, static files — is architecture-independent. Only the small runtime stage is assembled per
# target arch, so arm64 does not re-run Gradle/pnpm under emulation.
#
#   scripts/release-images.sh <version>
#   REGISTRY=ghcr.io/ggfto scripts/release-images.sh 0.2.0
set -euo pipefail

VERSION="${1:?usage: release-images.sh <version>}"
REGISTRY="${REGISTRY:-ghcr.io/ggfto}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"

# module name -> image name suffix. Each module has its own Dockerfile that builds from the repo root.
# `web` is the console: without it a deployment has an API and no user interface.
for module in gateway agent server web; do
  image="${REGISTRY}/hivekeeper-${module}"
  echo ">> building ${image}:${VERSION} for ${PLATFORMS}"
  # buildx builds the manifest list and pushes it directly — a multi-arch image cannot be loaded into the
  # local daemon, only pushed. Both tags are attached in the one build.
  docker buildx build \
    --platform "${PLATFORMS}" \
    -f "hive-${module}/Dockerfile" \
    -t "${image}:${VERSION}" \
    -t "${image}:latest" \
    --push \
    .
done

echo ">> published multi-arch (${PLATFORMS}) gateway/agent/server/web images at ${VERSION}"
