# Local Kubernetes (kind) targets, included from the root Makefile.

##@ Local Kubernetes (kind)

.PHONY: kind-up
kind-up: ## Create the kind cluster "yawar" + registry localhost:5001 + namespaces (idempotent)
	@bash deploy/kind/kind-up.sh

.PHONY: kind-down
kind-down: ## Delete the kind cluster and the local registry (KEEP_REGISTRY=1 keeps it)
	@bash deploy/kind/kind-down.sh

.PHONY: kind-smoke
kind-smoke: ## Check nodes, namespaces and push/pull through localhost:5001
	@bash deploy/kind/smoke-test.sh
