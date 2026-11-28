##@ Load tests (YAW-48, need the running stack)

# Same pinned k6 locally and in CI. Host network: the tokens' issuer must stay http://localhost:<KEYCLOAK_PORT>.
K6_IMAGE ?= grafana/k6:2.4.0@sha256:d41bb79cd8de5a7ad619b53570e3712633e8bff0937010a5c6c57a4747e7a276

.PHONY: load-pull
load-pull: ## Download the pinned k6 image
	@docker pull -q $(K6_IMAGE)

# PROFILE=smoke (default) | load | stress. The admin credentials reach k6 as environment variables, never as arguments.
.PHONY: load
load: export LOAD_PROFILE := $(PROFILE)
load: $(ENV_FILE) ## k6 load test of GET /donors/me through the gateway (PROFILE=smoke|load|stress)
	@mkdir -p load-tests/results
	@set -a && . ./$(ENV_FILE) && set +a && \
	  PROFILE=$${LOAD_PROFILE:-smoke} \
	  GATEWAY_URL=$${GATEWAY_URL:-http://localhost:$${GATEWAY_PORT:-8000}} \
	  KEYCLOAK_URL=$${KEYCLOAK_URL:-http://localhost:$${KEYCLOAK_PORT:-8080}} \
	  docker run --rm --network host --user "$$(id -u):$$(id -g)" -v "$(CURDIR)/load-tests:/scripts" -w /scripts \
	    -e PROFILE -e REALM -e GATEWAY_URL -e KEYCLOAK_URL -e LOAD_ALLOW_REMOTE_KEYCLOAK \
	    -e KEYCLOAK_ADMIN_USER -e KEYCLOAK_ADMIN_PASSWORD \
	    $(K6_IMAGE) run --quiet donors-me.js
