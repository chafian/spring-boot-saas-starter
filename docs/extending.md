# Add a tenant-owned feature

Use `project/` as the complete example. Keep new controllers, services, and
records in a feature package. Controllers validate input and take the actor
only from the verified JWT; they must not accept an actor, role, or tenant
context from a request body. A path tenant ID selects the scope.

1. Add a Flyway migration for the table with a non-null `tenant_id`, tenant
   foreign key, tenant-leading indexes, enabled and forced RLS, and both
   `USING` and `WITH CHECK` tenant policies. Grant only required operations to
   `saas_app`; never grant ownership or `TRUNCATE`.
2. Put the entire business operation in a public `@Transactional` service
   method invoked through Spring. Call `TenantAccess.requireMember` or
   `requireWriter` before querying tenant data. Those calls require an active
   transaction, lock membership, and activate transaction-local tenant context.
3. Include `tenant_id` predicates even though RLS is active. Use bound JDBC
   parameters. Never concatenate client input into SQL. Do not introduce
   unscoped repository methods or thread-local tenant state.
4. Record successful mutations through `AuditService.record` in the same
   transaction. Avoid storing request bodies, secrets, or tokens in audit/logs.
5. Use version checks for updates/deletes where concurrent edits matter.
   Return 404 for missing/out-of-tenant IDs and 409 for stale versions.
6. Add real PostgreSQL tests: user in tenant A cannot read/update/delete B;
   missing context sees nothing; forged tenant writes fail; commit/rollback
   clears context; permissions and audit behavior match the new feature.

The current tables `tenants` and `memberships` are a global control plane,
protected by service authorization rather than RLS. Any additions to that
surface require special review and cross-tenant tests. Do not generalize that
exception to business tables. RLS protects against missing predicates, not
malicious code that can set arbitrary context or a compromised database role.

A background worker has no web principal. Introduce explicit, audited service
identity authorization before adding one; do not call `activate` with an
untrusted job payload and assume that constitutes authorization.
