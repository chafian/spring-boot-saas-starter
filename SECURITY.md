# Security policy

This is a pre-release starter. Only the current main branch is maintained;
there is no long-term support commitment for the initial 0.1.0 snapshot.

Do not report exploitable vulnerabilities in public issues. When the repository
is published, use GitHub private vulnerability reporting after the maintainer
enables it. If it is unavailable, use a private contact channel explicitly
listed by the maintainer on their GitHub profile. Never include credentials or
customer data. Include affected commit/version, prerequisites, reproduction,
expected/actual behavior, and impact. No response-time guarantee is offered.

Review focus: JWT validation, tenant isolation, privilege escalation, unsafe
SQL, leaked credentials, dependency vulnerabilities, and insecure defaults.
Test using your own local environment and fictional data.

The Compose Keycloak realm is a local demo and is not a supported production
identity deployment. Before internet exposure, follow
[operations](docs/operations.md), including HTTPS, production OIDC, ingress
limits, secrets, and backups. RLS does not isolate tenants from a compromised
application or database administrator.
