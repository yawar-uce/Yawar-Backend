#!/usr/bin/env bash
# Builds the Docker image of every module that has a Dockerfile and scans it with Trivy (V-03).
# A HIGH or CRITICAL vulnerability with a fix available fails the script.
#
# Build context: the repository root when the Dockerfile copies yawar-platform (COPY/ADD, any flags, e.g.
# ms-donors), otherwise the module folder (e.g. config-server). Images are tagged yawar/<module>:<tag> (TAG, default: local); pushing to
# ECR is done by the CD workflow (YAW-16), not here.
#
# Usage: TAG=<sha> .github/scripts/build-images.sh [module ...]    (no arguments = every module)
# Env:   TRIVY_IMAGE (default aquasec/trivy:0.70.0, pinned by digest), TRIVY_CACHE_DIR (default ~/.cache/trivy)
set -euo pipefail

TAG=${TAG:-local}
# Same Trivy as the filesystem scan of ci.yml (trivy-action v0.36.0 → 0.70.0), pinned by digest.
TRIVY_IMAGE=${TRIVY_IMAGE:-aquasec/trivy:0.70.0@sha256:be1190afcb28352bfddc4ddeb71470835d16462af68d310f9f4bca710961a41e}
TRIVY_CACHE_DIR=${TRIVY_CACHE_DIR:-$HOME/.cache/trivy}
mkdir -p "$TRIVY_CACHE_DIR"

if [ $# -gt 0 ]; then
  modules=("$@")
else
  mapfile -t modules < <(for f in */Dockerfile; do [ -f "$f" ] && dirname "$f"; done)
fi
if [ ${#modules[@]} -eq 0 ]; then
  echo "No module has a Dockerfile yet: nothing to build."
  exit 0
fi

failed=()
for module in "${modules[@]}"; do
  dockerfile="$module/Dockerfile"
  [ -f "$dockerfile" ] || { echo "::error::$dockerfile not found"; exit 2; }
  if grep -qE '^(COPY|ADD)( --[^ ]+)* (\./)?yawar-platform' "$dockerfile"; then context=.; else context=$module; fi
  image="yawar/$module:$TAG"

  echo "::group::build $image (context: $context)"
  docker build --pull -f "$dockerfile" -t "$image" "$context"
  echo "::endgroup::"

  echo "::group::trivy $image"
  # Exit code 3 = vulnerabilities found; any other non-zero code is a scan error (network, disk), not a finding.
  rc=0
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$TRIVY_CACHE_DIR:/root/.cache/trivy" \
    "$TRIVY_IMAGE" image --quiet --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 3 "$image" \
    || rc=$?
  if [ "$rc" -eq 3 ]; then
    failed+=("$image")
  elif [ "$rc" -ne 0 ]; then
    echo "::error::Trivy could not scan $image (exit code $rc)"
    exit "$rc"
  fi
  echo "::endgroup::"
done

if [ ${#failed[@]} -gt 0 ]; then
  echo "::error::HIGH/CRITICAL vulnerabilities with a fix in: ${failed[*]}"
  exit 1
fi
echo "Built and scanned: ${modules[*]} (tag $TAG), no HIGH/CRITICAL findings."
