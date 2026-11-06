# Repository Guidelines

## Project Structure & Module Organization

This repository currently contains design documents and `script.sql`; application code is not scaffolded yet. Follow `docs/repository-structure.md` for the six planned Maven modules, the independent React/Vite `web/`, local `compose.yaml`, `config-repo/`, `infra/`, and CI workflows; implement the acceptance criteria in `docs/user-stories.md`. In each Java module, put code in `src/main/java`, Flyway migrations in `src/main/resources/db/migration`, and tests in `src/test/java`. Keep service databases and models separate.

## Build, Test, and Development Commands

After scaffolding, run `mvn verify` at the root for Java tests; Testcontainers requires Docker. Use `docker compose up --build -d` for the local stack and `docker compose down` to stop it without deleting volumes. In `web/`, run `npm ci`, `npm run dev`, and `npm run build`. The planned GitHub Actions runner runs locally and deploys with Compose on manual trigger. `script.sql` is a SQL reference, not a Flyway migration: split its per-database DDL into module migrations, then remove it. Never run both against the same tables.

## Coding Style & Naming Conventions

Use Java 21 and four-space indentation for Java; use two spaces for TypeScript, JSON, and YAML. Catalog, Order, and Notification use hexagonal architecture plus pragmatic DDD: each service owns a bounded context with top-level `domain/`, `application/ports/{in,out}/`, `application/service/`, and `infrastructure/adapters/{in,out}/`. Put invariants in aggregate roots and value objects; domain has no Spring/JPA imports and application never imports infrastructure. Keep support services simple. Name use cases `CheckoutOrderUseCase`, adapters `MercadoPagoAdapter`, and migrations `V1__create_orders.sql`. No formatter or linter is configured yet.

## Testing Guidelines

Use JUnit 5 and Testcontainers for PostgreSQL, Kafka, Redis, and Mailpit integration paths. Name unit tests `*Test` and container-backed tests `*IT`. Unit-test product publishing, order totals and status transitions; integrate idempotency cleanup, internal-route denial, Kafka duplicates and DLT, Catalog timeout (`503`), Redis fallback, streaming, concurrent webhooks, entitlements, outbox retry, and email failures. Stub Mercado Pago HTTP in CI; test manually with the application's test credentials.

## Commits, Pull Requests & Configuration

There is no Git history yet, so no existing commit convention can be inferred. Use scoped subjects such as `feat(orders): confirm test payment`. Pull requests should describe behavior, verification commands, related issues, and UI screenshots when relevant. Never commit Mercado Pago tokens, webhook secrets, Google OAuth credentials, Keycloak passwords, or `.env`; provide placeholders in `.env.example`. Only test credentials are allowed in the local payment flow.
