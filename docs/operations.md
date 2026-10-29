# Operations

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | Required | PostgreSQL JDBC URL |
| `DB_USER` | `saas_app` | Restricted runtime account |
| `DB_PASSWORD` | Required | Runtime database credential |
| `MIGRATIONS_ENABLED` | `true` | Run Flyway at startup; disable after separate migrations |
| `MIGRATION_USER` | `saas_migrator` | Schema owner used only by Flyway |
| `MIGRATION_PASSWORD` | Empty | Set when startup migrations are enabled |
| `OIDC_ISSUER` | Required | Exact trusted issuer, including realm path |
| `OIDC_JWK_SET_URI` | Required | Trusted endpoint for verifying signing keys |
| `OIDC_AUDIENCE` | `saas-api` | Required audience |

`.env` also supplies Compose-only `POSTGRES_PASSWORD`,
`KEYCLOAK_ADMIN_PASSWORD`, and `DEMO_PASSWORD`. Keep it out of source control.
The shell commands in the README export it only for local development.

The local issuer is `http://localhost:8081/realms/saas`. The app container
retrieves keys through `http://identity:8080/.../certs`, while still checking
the public issuer exactly. This distinction avoids hostname mismatches between
the host and Compose network. Key retrieval is lazy, so app health can be UP
while Keycloak is not ready; the smoke script checks both services.

## Production deployment

Use the application image with a managed PostgreSQL instance and a production
OIDC provider. Do not deploy `start-dev`, demo users, or the password-grant
client. Configure Authorization Code with PKCE for interactive clients, MFA
and account recovery in the provider, HTTPS issuer/JWKS URLs, and short-lived
access tokens. JWT validation is offline: IdP logout or user disablement does
not instantly invalidate an already-issued token. Tenant membership removal
is checked from PostgreSQL on each request and takes effect for subsequent
transactions. Requests already holding a membership lock can finish first.

Deploy behind TLS termination. Keep the app and database off public networks;
set ingress request-body limits (for example 16 KiB for this API), request
and connection timeouts, per-principal tenant-creation quotas, and rate limits.
`server.tomcat.max-http-form-post-size` limits form posts, **not JSON bodies**;
an ingress body cap is necessary. Keep forwarded headers disabled unless a
trusted proxy strips client-supplied forwarding headers. No CORS origins are
allowed by default. The API accepts no session cookies, so CSRF is disabled;
revisit CSRF protection if browser cookie authentication is added.

The supplied app container runs as UID 10001, drops Linux capabilities, uses a
read-only root filesystem with `/tmp`, and disallows privilege escalation.
Set platform memory/CPU limits and configure centralized log retention and
alerting. Health exposes only status; liveness checks the application state,
readiness also checks database connectivity. The identity provider is checked
separately. Do not expose diagnostic actuator endpoints publicly.

Use a secret manager for credentials; never put production values in the
Compose file or image. Use TLS database connections (`sslmode=verify-full`
and the proper CA configuration), encrypted storage, and separate credentials
per environment. The migration role owns the schema. The runtime role must
never own tables, create schema objects, be a superuser, or bypass RLS.
Startup checks enforce these basic privilege constraints.

For a deployment that keeps migration credentials out of serving processes:
run Flyway as a deployment job with the migration SQL directory, then start
the app with `MIGRATIONS_ENABLED=false` and runtime credentials only. Provision
`saas_app` and `saas_migrator` before migrations. The local bootstrap script
shows required roles and grants, but requires a database administrator and
assumes the database is named `saas`. Migration grants intentionally target
the `saas_app` role. To customize that role name, update migration grants too.

## Schema and data changes

Flyway uses ordered versioned SQL. Never edit a migration already applied to a
shared environment; add the next version. Use expand/contract changes so the
old and new application can coexist during rollout. Startup migrations are
convenient locally; coordinate them in production through the deployment job.
Forced RLS also applies to the migration table owner for DML: migrations that
change tenant data must explicitly establish the correct tenant context.
Superuser/bypass credentials must never be used by the application.

Back up PostgreSQL with point-in-time recovery appropriate to your service,
test restore procedures, and define recovery objectives before launch. A named
Docker volume is persistence, not a backup. Restore identity-provider state
alongside tenant data, preserving subject identifiers. Changing issuers or
recreating identities requires an explicit membership migration; a matching
email address does not reattach a membership.

Audit events are append-only for the application role, not tamper-proof against
DB administrators. They capture successful mutations, not rejected requests
or IdP login events. Export to a protected audit sink if your requirements
include independent retention, integrity proofs, or authentication history.
Define a retention/deletion policy for subjects and audit data.

## Troubleshooting

- **401 with a token:** verify exact issuer, audience, expiry, RS256 signature,
  key endpoint connectivity, and nonblank subject. Do not disable validation.
- **404 on a tenant:** authenticate as a member; headers and JWT roles cannot
  override database membership.
- **Startup privilege failure:** check `DB_USER` and grants. Keep migration
  and runtime credentials distinct.
- **Database connection failure after changing `.env`:** PostgreSQL init scripts
  run only on a fresh volume. Change database role passwords deliberately or
  recreate the disposable local database; changing `.env` alone does not rotate
  existing database credentials.
- **Demo login fails after editing realm.json:** realm import does not overwrite
  an existing realm. Recreate the local identity container and reset the demo
  database if subjects change. This erases demo state only when explicitly reset.
- **Port already in use:** stop the conflicting local service, or update Compose
  ports and all affected issuer/client URLs together.
- **Docker unavailable in tests:** start Docker and check `docker info` before
  retrying `./mvnw verify`.

## Release gate

Run `./mvnw verify`, a clean-volume Compose smoke run, and dependency/container
vulnerability scans. Review dependency licenses, support policy, backup restore,
identity configuration, ingress limits, and private vulnerability reporting.
Pin deployment images to reviewed digests and keep a patch-update process.
Check the repository Actions tab for hosted CI results before releasing.
Local setup scripts never create a GitHub repository or push source code.
