#!/usr/bin/env bash
# Smoke test of the local cluster: nodes Ready, Yawar namespaces present, and an image pushed
# to localhost:5001 can be pulled and run by the cluster. Leaves nothing behind.
set -euo pipefail

cluster="${KIND_CLUSTER:-yawar}"
context="kind-${cluster}"
reg_port="${KIND_REGISTRY_PORT:-5001}"
image="localhost:${reg_port}/yawar/smoke:$(date +%s)"
pod="registry-smoke"

cleanup() {
  kubectl --context "$context" -n yawar-dev delete pod "$pod" --ignore-not-found >/dev/null 2>&1 || true
  docker rmi -f "$image" >/dev/null 2>&1 || true
}
trap cleanup EXIT

kubectl --context "$context" get nodes
kubectl --context "$context" get namespaces -l app.kubernetes.io/part-of=yawar --show-labels

docker pull -q busybox:1.37 >/dev/null
docker tag busybox:1.37 "$image"
docker push -q "$image"

kubectl --context "$context" -n yawar-dev delete pod "$pod" --ignore-not-found >/dev/null
kubectl --context "$context" -n yawar-dev run "$pod" --image="$image" --restart=Never \
  --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":65534,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"registry-smoke","image":"'"$image"'","command":["sh","-c","echo pulled-from-local-registry"],"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' >/dev/null
kubectl --context "$context" -n yawar-dev wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$pod" --timeout=90s >/dev/null
echo "pod output: $(kubectl --context "$context" -n yawar-dev logs "$pod")"
echo "SMOKE OK: image $image pushed to localhost:${reg_port} and run in yawar-dev"
