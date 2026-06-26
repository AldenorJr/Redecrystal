# ──────────────────────────────────────────────────────────────────────────
# RedeCrystal — developer entrypoints.
# `make` targets shell out to Maven and Docker Compose. On Windows, run these
# from Git Bash / WSL (GNU make), or use the equivalent commands shown below.
# ──────────────────────────────────────────────────────────────────────────
.DEFAULT_GOAL := help
COMPOSE := docker compose

.PHONY: help build test package backend plugins sdk-it infra up down logs clean topics

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

build: ## Compile + test the whole Maven reactor
	mvn -B clean install

test: ## Run all tests
	mvn -B test

package: ## Build artifacts, skipping tests
	mvn -B clean package -DskipTests

backend: ## (Re)build and start the backend microservices (eureka, gateway, core-service)
	$(COMPOSE) up -d --build eureka-server core-service api-gateway

plugins: ## Build the plugin jars (crystal-core SDK + downstream)
	mvn -B -pl plugins/crystal-core -am package

sdk-it: ## Run the crystal-core integration test against the running stack
	CRYSTAL_IT=1 mvn -B -pl plugins/crystal-core -am test -Dtest=CrystalCoreIT -DfailIfNoTests=false

infra: ## Start only the data plane (postgres, redis, kafka, kafka-ui)
	$(COMPOSE) up -d postgres redis kafka kafka-init kafka-ui

up: ## Start the full stack in the background
	$(COMPOSE) up -d

down: ## Stop the stack (keeps volumes)
	$(COMPOSE) down

logs: ## Tail logs from all services
	$(COMPOSE) logs -f

topics: ## (Re)create Kafka topics against a running broker
	$(COMPOSE) exec kafka bash /scripts/create-topics.sh || \
		$(COMPOSE) run --rm kafka-init

clean: ## Stop the stack and remove volumes + Maven target dirs
	$(COMPOSE) down -v
	mvn -B clean || true
