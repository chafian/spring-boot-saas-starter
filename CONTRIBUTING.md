# Contributing

Use Java 25 and Docker, run `./mvnw verify`, and run the Compose smoke example
before submitting changes that affect authentication, persistence, or API
behavior. Format Java with `./mvnw spotless:apply`. Keep feature code together,
prefer constructor injection and explicit SQL, and add dependencies only when
they solve a concrete requirement.

Every persistence change needs a Flyway migration and tests against PostgreSQL.
Every tenant-owned feature needs cross-tenant denial tests. Do not mock away
security or database behavior being tested. Update README/API/operations docs
in the same change when contracts or setup steps change.

Use small pull requests that explain the problem, resulting behavior, and
validation. Keep secrets, private company code, customer data, real credentials,
and internal domains out of contributions. Examples use fictional identities.
Contributions are provided under this repository's MIT license.

Dependabot checks weekly and groups minor/patch version updates into one PR per
ecosystem: Maven, Docker, and GitHub Actions. Major upgrades remain separate for
compatibility review. Each ecosystem permits at most two open version-update
PRs. These grouping and queue rules do not delay or disable security updates.
Dependency updates still require review and passing CI; they do not auto-merge.

For security concerns, follow [SECURITY.md](SECURITY.md) instead of opening a
public issue. Treat contributors respectfully, discuss code rather than people,
and keep reviews constructive.
