# Spring Boot SaaS Starter

[![Verify](https://github.com/chafian/spring-boot-saas-starter/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/chafian/spring-boot-saas-starter/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A **multi-tenant Spring Boot SaaS starter** for Java developers building APIs
with OIDC authentication, tenant-scoped roles, and PostgreSQL row-level security.
Start with a working Projects API to explore membership checks, tenant isolation,
optimistic concurrency, and audit records before adding your own domain.

**Stack:** Java 25 · Spring Boot 4.1.2 · PostgreSQL · Keycloak for the local demo.  
[Quick start](#run-the-complete-example) · [API examples](docs/api.md#local-curl-walkthrough) ·
[Architecture](docs/architecture.md) · [Deployment](docs/operations.md) ·
[Contributing](CONTRIBUTING.md)

Maintained by [chafian](https://github.com/chafian). This repository contains
original starter code, public dependencies, and fictional examples.

## What is included

- Tenant creation and membership discovery, with OWNER / ADMIN / MEMBER roles.
- OIDC bearer authentication with signature, issuer, audience, subject, and expiry validation.
- PostgreSQL row security on project and audit data, plus explicit tenant predicates.
- An example Projects API with CRUD, pagination, validation, and optimistic concurrency.
- Transactional audit records and restricted runtime database credentials.
- Flyway migrations, Maven Wrapper, Docker Compose, and a local Keycloak realm.
- Real PostgreSQL integration tests, signed JWT tests, a runnable smoke example, and CI.

Redis is intentionally omitted: authentication is stateless, and there is no
shared cache or distributed job queue in this MVP. Add it only when an actual
feature requires it. Billing, a frontend, email invitations, ownership transfer,
and tenant deletion are outside this version.

## Run the complete example

Prerequisites: Git, a POSIX shell, Docker Engine with Compose v2, OpenSSL, and
Python 3.9+. Use a Linux/macOS terminal or a WSL environment with Docker access.
Ports 8080, 8081, and 5432 must be free. A host JDK is not needed for this path.

Run from a new checkout:

```sh
git clone https://github.com/chafian/spring-boot-saas-starter.git
cd spring-boot-saas-starter
./scripts/init-env.sh
docker compose up --build -d
python3 scripts/smoke.py
```

The first build downloads Java dependencies and container images. The smoke
script waits for readiness and checks real login, tenant isolation, roles,
project creation/update/deletion, stale-write rejection, and audit events.
It prints a single PASS line and never prints credentials or tokens. Each run
creates a fictional tenant and leaves its audit history in the local database.

Expected final output:

```text
PASS: real OIDC login, tenant isolation, membership, RBAC, CRUD, conflicts, and audit
```

Next, follow the [curl walkthrough](docs/api.md#local-curl-walkthrough) to create
a tenant and project yourself, or read [Adding a tenant-owned feature](docs/extending.md)
to adapt the example to your domain. The API has no frontend; use the readiness
URL below to check startup.

- API readiness: <http://localhost:8080/actuator/health/readiness>
- Keycloak administration: <http://localhost:8081/admin/> (`admin`, with the
  generated `KEYCLOAK_ADMIN_PASSWORD` in `.env`).
- Example identities: `alice` and `bob`, using the generated `DEMO_PASSWORD`.

All published ports bind to loopback. `.env` contains randomly generated local
credentials, has owner-only permissions, and is excluded from Git and Docker
build context. `init-env.sh` refuses to overwrite it.

```sh
docker compose logs --tail=100 app
docker compose down
```

`down` preserves the PostgreSQL volume. `docker compose down -v` deletes the
local example database. Keycloak is an ephemeral development instance: after
recreating it, reset the demo database too, because newly generated identity
subjects may differ. Never use this Compose setup as a production identity
service. See [operations](docs/operations.md).

### Quick-start troubleshooting

| Symptom | What to do |
| --- | --- |
| `.env already exists; refusing to replace credentials.` | Reuse the existing file and continue with Compose. The setup script deliberately preserves credentials. |
| `Service did not become ready:` from the smoke script | Inspect `docker compose logs --tail=100 app db identity`, resolve the startup failure, then rerun the smoke script. |
| A published port is already in use | Free ports 8080, 8081, and 5432, or update ports and affected issuer/client URLs together. |
| Login or database access fails after recreating services | Read the [identity and database reset guidance](docs/operations.md#troubleshooting) before changing credentials or deleting local data. |

## Develop and test

Install JDK 25 and `unzip`, and keep Docker running. Maven installs through the wrapper;
no global Maven installation is needed.

```sh
./mvnw verify
python3 -m unittest discover -s scripts -p 'test_*.py'
./mvnw spotless:apply
```

`verify` checks formatting, runs integration tests against a fresh PostgreSQL
Testcontainer, packages the app, and writes coverage to
`target/site/jacoco/index.html`. Docker is required; the tests do not silently
skip when Docker is unavailable. CI runs verification and the Compose smoke
example. Test reports are in `target/surefire-reports/`.

The Python tests check that the smoke script retries transient startup
connection failures and reports an error if readiness never succeeds.

To run the app from your IDE or Maven against local infrastructure, first generate
`.env` with `./scripts/init-env.sh` if it does not exist. Run these commands from
the repository root:

```sh
docker compose up -d db identity
set -a
. ./.env
set +a
export DB_URL=jdbc:postgresql://localhost:5432/saas
export OIDC_ISSUER=http://localhost:8081/realms/saas
export OIDC_JWK_SET_URI=http://localhost:8081/realms/saas/protocol/openid-connect/certs
./mvnw spring-boot:run
```

Stop a running Compose `app` first if it occupies port 8080.

## Authorization model

| Capability | OWNER | ADMIN | MEMBER |
| --- | :---: | :---: | :---: |
| Read projects and list tenant members | Yes | Yes | Yes |
| Create, update, delete projects | Yes | Yes | No |
| Read audit events | Yes | Yes | No |
| Add, change, remove non-owner members | Yes | No | No |
| Change or remove the owner | No | No | No |

Any authenticated identity may create a tenant and becomes its owner. Tenant
creation requires deployment-level quotas/rate limits before public exposure.
Memberships use the identity provider's immutable `sub`, not an email address.
A role in one tenant grants no access to another. Non-members receive 404 for
tenant resources. Existing members without a required role receive 403.

## Explore and extend

- [Architecture, repository structure, decisions, milestones, testing](docs/architecture.md)
- [API reference and curl examples](docs/api.md)
- [Deployment, configuration, backups, identity integration](docs/operations.md)
- [Adding a tenant-owned feature](docs/extending.md)
- [Validation and review record](docs/review.md)
- [Contribution guide](CONTRIBUTING.md) · [Security policy](SECURITY.md)

This is a tested starter MVP, not a certification of a deployment. Configure
TLS, production OIDC, rate limits, backups, monitoring, and secret management
for your environment before serving real customers.

## Related project

[Kubernetes Deployment Toolkit](https://github.com/chafian/k8s-deployment-toolkit)
provides a Helm chart and a separate Spring Boot deployment demo. Adapting this
SaaS API to Kubernetes requires its own database, OIDC configuration, credentials,
and application values; this repository's [operations guide](docs/operations.md)
describes those requirements.

## License

[MIT](LICENSE). Dependency licenses remain separate. No GitHub repository or
release is created by the local setup scripts.
