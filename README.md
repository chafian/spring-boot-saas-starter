# Spring Boot SaaS Starter

A generic, API-first foundation for a multi-tenant SaaS application, maintained
under the `chafian` namespace. Java 25, Spring Boot 4.1.2, PostgreSQL, and OIDC.
This repository is independent of private applications and contains only
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

Prerequisites: Docker Engine with Compose v2, OpenSSL, and Python 3.9+.
Ports 8080, 8081, and 5432 must be free. A host JDK is not needed for this path.

```sh
./scripts/init-env.sh
docker compose up --build -d
python3 scripts/smoke.py
```

The first build downloads Java dependencies and container images. The smoke
script waits for readiness and checks real login, tenant isolation, roles,
project creation/update/deletion, stale-write rejection, and audit events.
It prints a single PASS line and never prints credentials or tokens. Each run
creates a fictional tenant and leaves its audit history in the local database.

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

To run the app from your IDE or Maven against local infrastructure:

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

## License

[MIT](LICENSE). Dependency licenses remain separate. No GitHub repository or
release is created by the local setup scripts.
