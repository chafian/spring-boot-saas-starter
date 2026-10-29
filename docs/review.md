# Implementation review — 2026-10-29

This record describes the initial local MVP review, completed before
publication. The build and smoke commands below were exercised locally.
For current hosted CI results, see the repository Actions tab.

## Verification evidence

| Check | Result |
| --- | --- |
| Maven Wrapper, JDK 25, `./mvnw verify` | 31 tests passed; zero failures, errors, or skips |
| PostgreSQL integration | Real PostgreSQL 17.11 container; production Flyway migration; restricted `saas_app` role |
| Authentication | RSA-signed tokens and HTTP JWKS fixture; malformed/expired/wrong key/issuer/audience, missing subject/expiry/audience, future token rejected |
| Authorization | Cross-tenant reads/writes, role matrix, owner protection, membership removal and downgrade checked |
| Concurrency | Simultaneous project writes yield one success and one conflict; membership updates serialize |
| Database boundary | Unscoped reads, forged writes, audit immutability, privilege guard, commit/rollback context cleanup, atomic audit rollback checked |
| Complete local stack | Fresh-volume Docker build/start and real Keycloak smoke example passed |
| Runtime Java advisories | OSV query of 81 resolved runtime Maven packages returned no listed vulnerabilities on this date |
| Final application image | Trivy 0.76.1: no reported HIGH or CRITICAL vulnerabilities across detected Ubuntu, Java, and Go components |
| Documentation and source hygiene | Curl walkthrough passed, YAML/shell/Python syntax checked, local documentation links resolved, generated credentials absent from staged files |

Maven test details and coverage are generated under `target/`; they are excluded
from source control. The OSV result covers published package advisories, not
undisclosed vulnerabilities, deployment configuration, or every dependency license.
The image scan used image ID
`sha256:23baa9fb889698721514e17c94e3e8e923c94ffb871a99de8e0cb464d0f94ea9`
on macOS ARM64 with Docker Desktop. It filtered to HIGH and CRITICAL severity;
lower-severity findings and other architectures were not assessed. Keycloak and
PostgreSQL images were exercised functionally but not included in this image scan.
GitHub Actions YAML was validated locally. Hosted results are recorded in
[GitHub Actions](https://github.com/chafian/spring-boot-saas-starter/actions/workflows/ci.yml).

## Findings resolved during implementation

- `tenancy/TenantAccess.java` — replaced a tenant-row lock requiring forbidden
  UPDATE privileges with a direct owner-membership lock. It also avoids
  shared-to-exclusive lock upgrades during concurrent owner requests.
- `security/SecurityConfig.java` — explicitly rejects a missing audience instead
  of throwing a null-pointer exception. Added a signed-token regression case.
- `common/DatabasePrivileges.java` — also rejects a missing protected schema,
  in addition to dangerous runtime roles, ownership, and disabled RLS.
- `tenancy/TenantController.java` — creation now returns the canonical tenant
  URL, backed by an authorized tenant-read endpoint.
- `Dockerfile` — installed `unzip` so the wrapper consistently verifies the
  pinned ZIP checksum instead of falling back to a different archive format.
- `scripts/smoke.py` — requested OpenID scope for the user-info call and handled
  transient connection closure while services start.

clean-code-guard: 4 production-code findings fixed, 0 unresolved findings flagged.
The test-guard pass found no internal mocks or redundant framework-only tests;
variants share parameterized tests. The docs-guard pass checked the routes,
configuration, commands, and local example against the implementation.

The first hosted Compose run exposed a connection-reset race while Spring was
starting. Readiness polling now retries connection failures within its existing
bounded wait. Two Python regression tests cover recovery and persistent failure;
CI runs them alongside the Java suite. Business requests are not automatically
retried.

## Deliberate boundaries

- `tenants` and `memberships` are a global control plane protected by service
  authorization. RLS covers projects and audit events. It does not defend
  against malicious application code or a database administrator.
- Local Keycloak, its password-grant client, and Compose credentials are for
  development. A public deployment requires production OIDC, HTTPS, request
  size/rate limits, tenant-creation quotas, secrets, monitoring, and backups.
- Stateless JWTs can remain valid until expiry after IdP logout. Tenant role
  changes are checked per transaction; already-authorized in-flight work can
  complete before a membership mutation takes its lock.
- No billing, invitation email, UI, ownership transfer, tenant deletion,
  idempotency-key support, load-test results, or compliance certification is
  claimed for this MVP.

## Release checks

Review the scope and MIT license, private vulnerability reporting, branch
protections, and current GitHub checks before making a release.
Re-run dependency and image scans at release time; a dated clean scan is not
a permanent security claim.
