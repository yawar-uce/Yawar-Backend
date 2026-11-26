# Build and test targets (included by the Makefile). They run the Maven wrapper on the root aggregator pom.
# S=<folder> limits them to one module (e.g. S=ms-donors, S=yawar-platform): its dependencies are installed
# first without tests, then only that module's tests run. Without S every module is built and tested.
#
# Test layers (testing contract, every module follows the same naming):
#   *Test.java            unit tests          (surefire)   make test
#   *IT.java              integration tests   (failsafe)   make test-it
#   *AcceptanceIT.java    one per Gherkin scenario, through the API (hu01_sc1_…)   make acceptance
#   *ContractIT.java      API matches OpenAPI/AsyncAPI (V-02)                    make contract
# Each module's pom exposes the property skipUTs (surefire <skipTests>${skipUTs}</skipTests>) and runs JaCoCo
# with a 70 % line gate at verify (V-01).

MVN := ./mvnw -B -ntp

# Dependencies of S (e.g. yawar-platform for ms-donors) go to ~/.m2 first, without tests.
define mvn_scoped
	@if [ -n "$(S)" ]; then \
	  [ -f "$(S)/pom.xml" ] || { echo "No module '$(S)': use a top-level folder name (e.g. S=ms-donors)"; exit 2; }; \
	  $(MVN) -q -pl $(S) -am install -DskipTests -Djacoco.skip=true && \
	  $(MVN) -pl $(S) $(1); \
	else \
	  $(MVN) $(1); \
	fi
endef

IT_FLAGS := -DskipUTs=true -Djacoco.skip=true -Dit.failIfNoSpecifiedTests=false

##@ Build and test (S=<folder> for one module)

.PHONY: build
build: ## Compile and package without tests
	$(call mvn_scoped,package -DskipTests -Djacoco.skip=true)

.PHONY: test
test: ## Unit tests (JUnit 5 + Mockito + AssertJ)
	$(call mvn_scoped,test)

.PHONY: test-it
test-it: ## Integration tests with Testcontainers (Docker must be running)
	$(call mvn_scoped,verify $(IT_FLAGS) '-Dit.test=*IT$(,)!*AcceptanceIT$(,)!*ContractIT')

.PHONY: acceptance
acceptance: ## Functional/acceptance tests, one per Gherkin scenario
	$(call mvn_scoped,verify $(IT_FLAGS) '-Dit.test=*AcceptanceIT')

.PHONY: contract
contract: ## Contract tests against OpenAPI/AsyncAPI (V-02); CONTRACT_UPDATE=true rewrites docs/openapi/<ms>.yaml
	$(call mvn_scoped,verify $(IT_FLAGS) '-Dit.test=*ContractIT' $(if $(CONTRACT_UPDATE),-Dcontract.update=true))

.PHONY: coverage
coverage: ## All tests + JaCoCo gate; prints line coverage and the HTML report path per module
	@rc=0; $(MAKE) --no-print-directory _verify S=$(S) || rc=$$?; \
	for csv in $(if $(S),$(S),*)/target/site/jacoco/jacoco.csv; do \
	  [ -f "$$csv" ] || continue; \
	  mod=$${csv%%/*}; \
	  awk -F, -v m="$$mod" 'NR>1 {miss+=$$8; cov+=$$9} END {if (miss+cov>0) printf "%-20s line coverage %5.1f %%  ->  %s/target/site/jacoco/index.html\n", m, 100*cov/(miss+cov), m}' "$$csv"; \
	done; exit $$rc

.PHONY: _verify
_verify:
	$(call mvn_scoped,verify)

##@ End-to-end tests (YAW-47, need the running stack)

# Runs e2e-tests/ against the stack of `make up PROFILE=platform` + `make keycloak-import`. The Keycloak admin
# credentials and ports come from deploy/local/.env (GATEWAY_URL / KEYCLOAK_URL / REALM override the defaults).
.PHONY: e2e
e2e: $(ENV_FILE) ## API-level E2E tests through the API Gateway (stack up and realm imported; see e2e-tests/README.md)
	@set -a && . ./$(ENV_FILE) && set +a && \
	  GATEWAY_URL=$${GATEWAY_URL:-http://localhost:$${GATEWAY_PORT:-8000}} \
	  KEYCLOAK_URL=$${KEYCLOAK_URL:-http://localhost:$${KEYCLOAK_PORT:-8080}} \
	  $(MVN) -f e2e-tests/pom.xml verify
