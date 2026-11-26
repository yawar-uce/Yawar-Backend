##@ Samba AD yawar.lab (YAW-30)

AD_SERVICE := samba-ad

.PHONY: ad-up
ad-up: ## Start the local stack with Samba AD (same as make up PROFILE=ad)
	$(MAKE) up PROFILE=ad

.PHONY: ad-seed
ad-seed: $(ENV_FILE) ## Create OUs, groups and the 10 staff users (idempotent; ARGS=--reset-passwords / --must-change)
	ENV_FILE='$(ENV_FILE)' deploy/ad/seed-users.sh $(ARGS)

.PHONY: ad-test
ad-test: $(ENV_FILE) ## Simple bind over LDAPS for the 10 users (prints "10/10 binds OK")
	ENV_FILE='$(ENV_FILE)' deploy/ad/test-binds.sh

.PHONY: ad-users
ad-users: $(ENV_FILE) ## List the staff users with group and site (no passwords)
	@$(COMPOSE) exec -T $(AD_SERVICE) bash -s < deploy/ad/list-users.sh

.PHONY: ad-cert
ad-cert: $(ENV_FILE) ## Export the lab CA to deploy/ad/.secrets/ad-ca.pem (Keycloak truststore)
	@mkdir -p deploy/ad/.secrets
	@$(COMPOSE) exec -T $(AD_SERVICE) cat /var/lib/samba/tls/ca.pem > deploy/ad/.secrets/ad-ca.pem.tmp \
	  && grep -q 'BEGIN CERTIFICATE' deploy/ad/.secrets/ad-ca.pem.tmp \
	  && chmod 644 deploy/ad/.secrets/ad-ca.pem.tmp \
	  && mv deploy/ad/.secrets/ad-ca.pem.tmp deploy/ad/.secrets/ad-ca.pem \
	  && echo "Wrote deploy/ad/.secrets/ad-ca.pem" \
	  || { rm -f deploy/ad/.secrets/ad-ca.pem.tmp; echo "ad-cert failed: is samba-ad running?" >&2; exit 1; }
