##@ Keycloak realm as code (YAW-31)

# REALM=yawar-dev (default) or REALM=yawar-qa: file deploy/keycloak/realms/$(REALM).yaml
REALM ?= yawar-dev

.PHONY: keycloak-import
keycloak-import: $(ENV_FILE) ## Apply deploy/keycloak/realms/$(REALM).yaml to the local Keycloak (idempotent)
	KC_IMPORT_REALM=$(REALM) $(COMPOSE) run --rm keycloak-config

.PHONY: keycloak-check
keycloak-check: $(ENV_FILE) ## Check the realm: settings, roles, PKCE login, sign-up with consent, email (REALM=…)
	@# Same KC_WEB_URL as the import (deploy/local/.env), also when testing the yawar-qa file locally.
	@set -a && . ./$(ENV_FILE) && set +a && KC_REALM=$(REALM) python3 -I deploy/keycloak/test_realm.py

##@ Staff accounts from Active Directory (YAW-32)

# Needs `make up PROFILE=ad`, `make ad-seed`, `make ad-cert` (then restart keycloak) and the realm imported with KC_AD_ENABLED=true.
.PHONY: ad-federation-check
ad-federation-check: $(ENV_FILE) ## Check the AD federation: provider, sync of 10 users, role and site per user, disabled account, read-only
	@set -a && . ./$(ENV_FILE) && set +a && KC_REALM=$(REALM) python3 -I deploy/keycloak/test_federation.py check

.PHONY: token
token: export TOKEN_USER := $(USER)
token: $(ENV_FILE) ## Sign in an AD user with the PKCE flow and print the DECODED claims (USER=yw.recruit01), never the token
	@set -a && . ./$(ENV_FILE) && set +a && KC_REALM=$(REALM) python3 -I deploy/keycloak/test_federation.py token "$$TOKEN_USER"
