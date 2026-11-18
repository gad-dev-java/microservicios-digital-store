# Repository Guidelines

## Project Structure & Module Organization

The root `pom.xml` builds six Spring Boot modules under `services/`: Catalog, Order, Notification, Gateway, Config Server, and Discovery Server. `web/` holds React/Vite/TypeScript; `compose.yaml`, `config-repo/`, and `infra/` hold local infrastructure. Use [architecture](docs/architecture.md) for boundaries, [repository structure](docs/repository-structure.md) for paths, and [user stories](docs/user-stories.md) for scope and acceptance. For API or messaging changes, read [API conventions](docs/api-conventions.md), the [HTTP contract](docs/contracts/http-api-v1.yaml), and the [ORDER_PAID schema](docs/contracts/order-paid-v1.schema.json). Follow [observability](docs/observability.md) for traces and logs and the [download ADR](docs/adr/001-streaming-descargas.md) for file delivery. HT-10–HT-19 are planned work; check the code before assuming they exist. Put Java code in `src/main/java`, tests in `src/test/java`, and Flyway migrations in `src/main/resources/db/migration`. `script.sql` is a reference; never apply it over Flyway-managed tables.

## Build, Test & Local Development

Run `mvn verify` at the root for all Java modules (`./mvnw verify` in Bash/WSL); it checks Java format and tests. Run `mvn spotless:apply` to format Java. In `web/`, use `npm ci`, `npm run dev`, `npm run lint`, and `npm run build`. Use `docker compose up --build -d` to start locally and `docker compose down` to stop without deleting volumes. CI uses a trusted Linux/WSL runner; `deploy-local.yml` runs manually from `master`.

## Architecture & Coding Style

Use Java 21 and Spotless for four-space Java formatting; use two spaces for TypeScript, JSON, and YAML. Catalog, Order, and Notification follow hexagonal architecture with pragmatic DDD: add `domain/`, `application/ports/{in,out}/`, `application/service/`, and `infrastructure/adapters/{in,out}/` as features grow. Domain must not import Spring/JPA; application must not import infrastructure. Use constructor injection, HTTP DTOs, thin controllers, and manual mappers in adapters. Name classes `CheckoutOrderUseCase` or `MercadoPagoAdapter` and migrations `V2__add_catalog_indexes.sql`. Run Oxlint with `npm run lint`.

## Testing Guidelines

Use JUnit Jupiter 6; name unit/security tests `*Test` and container-backed integration tests `*IT`. Add Testcontainers for database, Kafka, Redis, and SMTP paths as features arrive. Stub Mercado Pago in CI; use test credentials manually. Before a pull request, run `mvn verify`, `npm run lint`, and `npm run build`.

## Commits, Pull Requests & Secrets

Use scoped commits such as `feat(auth): complete HU-02 login and route security`. Keep each story on a branch; pull requests should list behavior, verification, related HU/HT, and UI screenshots when relevant. Never commit `.env`, credentials, JWTs, or client secrets. Use `.env.example` placeholders. Keep Keycloak's `order-service-client` secret in `infra/keycloak/digital-store-realm.json` as `${ORDER_SERVICE_CLIENT_SECRET}`; console exports mask it. Web tokens stay in memory; only the Order service account receives `CATALOG_INTERNAL_READ`.
