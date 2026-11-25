#!/usr/bin/env bash
# Builds the Docker image of every module that has a Dockerfile and scans it with Trivy (V-03).
# A HIGH or CRITICAL vulnerability with a fix available fails the script.
#
# Build context: the repository root when the Dockerfile copies yawar-platform (e.g. ms-donors), otherwise the
# module folder (e.g. config-server). Images are tagged yawar/<module>:<tag> (TAG, default: local); pushing to
# ECR is done by the CD workflow (YAW-16), not here.
#
# Usage: TAG=<sha> .github/scripts/build-images.sh [module ...]    (no arguments = every module)
# Env:   TRIVY_IMAGE (default aquasec/trivy:0.58.1), TRIVY_CACHE_DIR (default ~/.cache/trivy)
set -euo pipefail

TAG=${TAG:-local}
TRIVY_IMAGE=${TRIVY_IMAGE:-aquasec/trivy:0.58.1}
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
  if grep -q '^COPY yawar-platform' "$dockerfile"; then context=.; else context=$module; fi
  image="yawar/$module:$TAG"

  echo "::group::build $image (context: $context)"
  docker build --pull -f "$dockerfile" -t "$image" "$context"
  echo "::endgroup::"

  echo "::group::trivy $image"
  if ! docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$TRIVY_CACHE_DIR:/root/.cache/trivy" \
      "$TRIVY_IMAGE" image --quiet --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 "$image"; then
    failed+=("$image")
  fi
  echo "::endgroup::"
done

if [ ${#failed[@]} -gt 0 ]; then
  echo "::error::HIGH/CRITICAL vulnerabilities with a fix in: ${failed[*]}"
  exit 1
fi
echo "Built and scanned: ${modules[*]} (tag $TAG), no HIGH/CRITICAL findings."
