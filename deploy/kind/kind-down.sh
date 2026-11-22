#!/usr/bin/env bash
# Deletes the local kind cluster "yawar" and the local registry created by kind-up.sh. Idempotent.
# KEEP_REGISTRY=1 keeps the registry container. Pushed images live in the volume <registry>-data,
# which is kept; PURGE_REGISTRY=1 deletes it too.
set -euo pipefail

cluster="${KIND_CLUSTER:-yawar}"
reg_name="${KIND_REGISTRY_NAME:-yawar-registry}"

docker info >/dev/null 2>&1 || { echo "Docker is not running" >&2; exit 1; }

if kind get clusters | grep -qx "$cluster"; then
  kind delete cluster --name "$cluster"
else
  echo "Cluster $cluster does not exist"
fi

if [ "${KEEP_REGISTRY:-0}" != "1" ] && docker inspect "$reg_name" >/dev/null 2>&1; then
  # Only remove a registry that kind-up.sh created (never someone else's container).
  if [ "$(docker inspect -f '{{index .Config.Labels "yawar.io/managed"}}' "$reg_name")" = "kind" ]; then
    docker rm -f "$reg_name" >/dev/null
    echo "Removed registry $reg_name"
  else
    echo "Container $reg_name was not created by Yawar; left untouched"
  fi
fi

if [ "${PURGE_REGISTRY:-0}" = "1" ] && docker volume inspect "${reg_name}-data" >/dev/null 2>&1; then
  docker volume rm "${reg_name}-data" >/dev/null
  echo "Removed volume ${reg_name}-data"
fi
