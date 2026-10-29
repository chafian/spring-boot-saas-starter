# API reference

Base path: `/api/v1`. Send `Authorization: Bearer <access_token>` and
`Content-Type: application/json` for request bodies. Cookies do not authenticate
requests. Only RS256 signed access tokens for the configured issuer and
`saas-api` audience are accepted. Refresh, login, logout, recovery, and MFA
belong to the identity provider.

## Routes

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| POST | `/tenants` | Authenticated | 201 tenant |
| GET | `/tenants` | Authenticated; lists own memberships | 200 array |
| GET | `/tenants/{tenantId}` | Any tenant member | 200 tenant |
| GET | `/tenants/{tenantId}/members` | Any tenant member | 200 array |
| PUT | `/tenants/{tenantId}/members` | OWNER | 204 |
| DELETE | `/tenants/{tenantId}/members?subject={subject}` | OWNER | 204 |
| GET | `/tenants/{tenantId}/projects` | Any tenant member | 200 array |
| POST | `/tenants/{tenantId}/projects` | OWNER or ADMIN | 201 project |
| GET | `/tenants/{tenantId}/projects/{id}` | Any tenant member | 200 project |
| PUT | `/tenants/{tenantId}/projects/{id}` | OWNER or ADMIN | 200 updated project |
| DELETE | `/tenants/{tenantId}/projects/{id}?version={version}` | OWNER or ADMIN | 204 |
| GET | `/tenants/{tenantId}/audit-events` | OWNER or ADMIN | 200 array |

Every list accepts `limit` (default 20, 1–100) and `offset` (default 0,
0–10000). Projects and tenants order by creation time then ID; audit events
order newest first, then ID; memberships order by subject. Offset pagination
is not a snapshot: concurrent inserts/deletes can move items between pages.
There is no total-count query. Use a shorter page as the end-of-results signal.

## Local curl walkthrough

Requires the running Compose environment, Bash-compatible shell, `curl`, and
`jq`. The local-only client uses the password grant so scripts can obtain
tokens without a browser. Production clients should use Authorization Code
with PKCE; do not enable the demo password grant in production.

```sh
set -a
. ./.env
set +a
TOKEN=$(curl --fail --silent --show-error \
  http://localhost:8081/realms/saas/protocol/openid-connect/token \
  --data-urlencode grant_type=password \
  --data-urlencode scope=openid \
  --data-urlencode client_id=saas-cli \
  --data-urlencode username=alice \
  --data-urlencode "password=$DEMO_PASSWORD" | jq -er .access_token)

TENANT_ID=$(curl --fail --silent --show-error http://localhost:8080/api/v1/tenants \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Example workspace"}' | jq -er .id)

PROJECT_ID=$(curl --fail --silent --show-error \
  "http://localhost:8080/api/v1/tenants/$TENANT_ID/projects" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Example project","description":"First task"}' | jq -er .id)

curl --fail --silent --show-error -X PUT \
  "http://localhost:8080/api/v1/tenants/$TENANT_ID/projects/$PROJECT_ID" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Updated project","description":"New text","version":0}'

# The previous update incremented the version. This stale delete returns 409.
curl --silent --show-error -i -X DELETE \
  "http://localhost:8080/api/v1/tenants/$TENANT_ID/projects/$PROJECT_ID?version=0" \
  -H "Authorization: Bearer $TOKEN"
```

The update response includes `id`, `tenantId`, `name`, `description`,
`version`, `createdAt`, and `updatedAt`. Send the returned version for the next
update or deletion. On 409, fetch the current project and reconcile changes
before retrying. Do not automatically replace the version and overwrite data.

Tenant input is `{ "name": "Example workspace" }`; the response includes
`id`, `name`, and `role`. Names must be nonblank and at most 100 characters.
Project descriptions are required (empty string is allowed), at most 2000
characters. Unknown request fields are rejected to catch accidental mass
assignment and typos. IDs, tenant ID, audit fields, and timestamps are
server-controlled. Project version is a nonnegative integer.

Membership PUT accepts `{ "subject": "idp-user-subject", "role": "MEMBER" }`
or `ADMIN`. It creates or replaces the membership. Only add subjects verified
through your identity provider; the starter does not query an IdP user directory
or send invitations. The subject is case-sensitive, nonblank, at most 200
characters. URL-encode it in the DELETE query. OWNER assignment or transfer is
unsupported. Attempts to remove/demote the existing owner return 409.

Audit responses contain `id`, `actor`, `action`, `resourceId`, `occurredAt`.
Actions include `TENANT_CREATED`, `MEMBERSHIP_SET_ADMIN`,
`MEMBERSHIP_SET_MEMBER`, `MEMBERSHIP_REMOVED`, `PROJECT_CREATED`,
`PROJECT_UPDATED`, and `PROJECT_DELETED`. Actor and membership resource IDs
are identity subjects; treat audit exports as potentially personal data.

## Failures

| Status | Meaning | Client action |
| --- | --- | --- |
| 400 | Invalid body, UUID, pagination, version, or unsupported owner assignment | Correct request |
| 401 | Missing, malformed, expired, or untrusted bearer token | Obtain a valid access token |
| 403 | Member lacks the required tenant role | Request permission from the owner |
| 404 | Missing resource or caller is not a tenant member | Check selected tenant and resource |
| 409 | Stale project version or immutable owner operation | Reload/reconcile, or use a supported operation |
| 503 | Database operation unavailable | Inspect server/database health; retry only when safe |

Application errors use `application/problem+json`, with `status`, `title`,
and `detail`; MVC may also add `instance`. Authentication failures return
`status` and `title` with `WWW-Authenticate: Bearer`. Database errors do not
expose SQL or connection details. A sample conflict body is:

```json
{"status":409,"title":"Conflict","detail":"Project version changed; reload before retrying"}
```

POST operations do not support idempotency keys. After an ambiguous network
failure, check whether creation succeeded before retrying. There is no
cross-origin browser allowlist configured; use a same-origin gateway or add a
reviewed exact-origin CORS policy for your frontend.
