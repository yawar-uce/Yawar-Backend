# Yawar backend — single entry point for local work.
# Run `make help` to list the targets.

SHELL := /bin/bash
.DEFAULT_GOAL := help

LOCAL_DIR := deploy/local
ENV_FILE  := $(LOCAL_DIR)/.env
COMPOSE   := docker compose --env-file $(ENV_FILE) -f $(LOCAL_DIR)/docker-compose.yml

# Optional compose profile(s), e.g. `make up PROFILE=ad` or `make up PROFILE="ad gateway"`.
PROFILE ?=
PROFILE_FLAGS := $(foreach p,$(PROFILE),--profile $(p))

# `make logs S=keycloak` · `make psql DB=donors_db` · `make psql SQL='\l'`
S   ?=
DB  ?= postgres
SQL ?=

.PHONY: help
help: ## List the available targets
	@awk 'BEGIN {FS = ":.*## "} /^##@/ {printf "\n\033[1m%s\033[0m\n", substr($$0, 5)} /^[a-zA-Z0-9_-]+:.*## / {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

##@ Local environment (docker compose)

$(ENV_FILE): $(LOCAL_DIR)/.env.example
	@if [ -f $(ENV_FILE) ]; then \
	  echo "WARNING: $(LOCAL_DIR)/.env.example is newer than $(ENV_FILE); copy any new keys into it."; \
	  touch $(ENV_FILE); \
	else \
	  cp $(LOCAL_DIR)/.env.example $(ENV_FILE); \
	  echo "Created $(ENV_FILE) from .env.example (edit it to change local credentials)."; \
	fi

.PHONY: up
up: $(ENV_FILE) ## Start the local stack and wait until healthy (PROFILE=ad for Samba AD)
	$(COMPOSE) $(PROFILE_FLAGS) up -d --wait --wait-timeout 300

.PHONY: down
down: $(ENV_FILE) ## Stop the local stack (keeps data volumes)
	$(COMPOSE) --profile '*' down

.PHONY: reset
reset: $(ENV_FILE) ## Stop the stack and delete all local data volumes
	$(COMPOSE) --profile '*' down --volumes --remove-orphans

.PHONY: ps
ps: $(ENV_FILE) ## Show the containers and their health
	$(COMPOSE) --profile '*' ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'

.PHONY: logs
logs: $(ENV_FILE) ## Follow logs (S=<service> for one service)
	$(COMPOSE) --profile '*' logs -f --tail=200 $(S)

.PHONY: psql
psql: export PSQL_DB := $(DB)
psql: export PSQL_SQL := $(SQL)
psql: $(ENV_FILE) ## Open psql as the admin user (DB=<database>, SQL='<command>' to run one command)
	@if [ -n "$$PSQL_SQL" ]; then \
	  $(COMPOSE) exec -T -e PSQL_DB -e PSQL_SQL postgres sh -c 'psql -U "$$POSTGRES_USER" -d "$$PSQL_DB" -c "$$PSQL_SQL"'; \
	else \
	  $(COMPOSE) exec -e PSQL_DB postgres sh -c 'psql -U "$$POSTGRES_USER" -d "$$PSQL_DB"'; \
	fi

# Each area keeps its targets in its own file to avoid conflicts between tasks.
include deploy/kind/kind.mk
