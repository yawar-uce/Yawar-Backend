#!/usr/bin/env bash
# Creates (or reconciles) the local kind cluster "yawar" and the registry localhost:5001.
# Idempotent: every step checks the current state first, so it is safe to run again
# (also after a Docker Desktop / WSL restart or a partial previous run).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cluster="${KIND_CLUSTER:-yawar}"
context="kind-${cluster}"
reg_name="${KIND_REGISTRY_NAME:-yawar-registry}"
reg_port="${KIND_REGISTRY_PORT:-5001}"
reg_image="${KIND_REGISTRY_IMAGE:-registry:3.1.2}"
reg_label="yawar.io/managed=kind"

docker info >/dev/null 2>&1 || { echo "Docker is not running" >&2; exit 1; }

# 1. Local registry container (only on 127.0.0.1, images kept in a named volume).
if docker inspect "$reg_name" >/dev/null 2>&1; then
  if [ "$(docker inspect -f '{{index .Config.Labels "yawar.io/managed"}}' "$reg_name")" != "kind" ]; then
    echo "A container named $reg_name exists but was not created by Yawar; set KIND_REGISTRY_NAME" >&2
    exit 1
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$reg_name")" != "true" ]; then
    echo "Starting existing registry $reg_name"
    docker start "$reg_name" >/dev/null
  fi
else
  echo "Creating registry $reg_name on localhost:$reg_port"
  docker run -d --restart=always --label "$reg_label" \
    -p "127.0.0.1:${reg_port}:5000" -v "${reg_name}-data:/var/lib/registry" \
    --name "$reg_name" "$reg_image" >/dev/null
fi

# 2. Cluster (start its node containers again if Docker was restarted).
if kind get clusters 2>/dev/null | grep -qx "$cluster"; then
  echo "Cluster $cluster already exists"
  for node in $(kind get nodes --name "$cluster"); do
    if [ "$(docker inspect -f '{{.State.Running}}' "$node")" != "true" ]; then
      echo "Starting stopped node $node"
      docker start "$node" >/dev/null
    fi
  done
else
  kind create cluster --name "$cluster" --config "$here/cluster.yaml" --wait 120s
fi

# 3. Tell containerd on every node that localhost:5001 is the registry container.
reg_dir="/etc/containerd/certs.d/localhost:${reg_port}"
for node in $(kind get nodes --name "$cluster"); do
  docker exec "$node" mkdir -p "$reg_dir"
  printf '[host."http://%s:5000"]\n' "$reg_name" | docker exec -i "$node" cp /dev/stdin "$reg_dir/hosts.toml"
done

# 4. Connect the registry to the kind network (no-op if already connected).
if [ "$(docker inspect -f '{{json .NetworkSettings.Networks.kind}}' "$reg_name")" = "null" ]; then
  docker network connect kind "$reg_name"
fi

# 5. Document the registry for tools (KEP-1755) and create the Yawar namespaces.
kubectl config use-context "$context" >/dev/null
# After a restart the API server needs a few seconds before kubectl calls succeed.
for _ in $(seq 1 60); do
  kubectl --context "$context" get --raw=/readyz >/dev/null 2>&1 && break
  sleep 2
done
kubectl --context "$context" get --raw=/readyz >/dev/null
kubectl --context "$context" wait --for=condition=Ready nodes --all --timeout=120s
kubectl --context "$context" apply -f - <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "localhost:${reg_port}"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
EOF
kubectl --context "$context" apply -f "$here/namespaces.yaml"

echo "kind cluster $cluster ready (context $context, registry localhost:$reg_port)"
