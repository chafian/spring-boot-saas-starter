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

## Tenant-aware constraints

[PostgreSQL integrity checks bypass RLS](https://www.postgresql.org/docs/17/ddl-rowsecurity.html).
A row being visible only to tenant A does not ensure that its referenced parent
also belongs to A. Constraints must encode that relationship.

For example, if extending this starter with a tenant-owned `tasks` table:

- Add `UNIQUE (tenant_id, id)` to `projects` in a new Flyway migration so it
  can be the target of a composite foreign key.
- Give each task non-null `tenant_id` and `project_id` columns and declare
  `FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id)`.
  A foreign key on `project_id` alone cannot enforce the same-tenant rule.
- If tasks have a required slug unique within a tenant, make it non-null and
  use `UNIQUE (tenant_id, slug)`, without an additional global `UNIQUE (slug)`.

These are extension patterns, not existing task or slug features. Keep the RLS,
authorization, and indexing requirements above. A globally unique generated UUID
primary key can remain; it does not replace the composite relationship constraint.
See PostgreSQL's [constraint reference](https://www.postgresql.org/docs/17/ddl-constraints.html)
for referenced-key and null-handling requirements. Add a new migration rather
than editing an already-applied migration.

## Background jobs and asynchronous work

The current starter does not provide a background-worker authorization model.
Before introducing `@Async` methods or scheduled jobs, define an explicit service
identity and which tenants and operations it may access. A tenant ID in a job
payload is a scope selector, not proof of permission.

For this JDBC application, a request's transaction does not propagate to a new
worker thread; see [Spring's transaction model](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html).
Have the worker invoke a separate Spring-managed service's public
`@Transactional` method. Within that transaction, authorize the job identity for
the tenant, establish transaction-local context, perform the operation, and
record successful mutations through `AuditService.record`.

If the identity is represented by a database membership, use
`TenantAccess.requireMember` or `requireWriter` as appropriate. Other service
identities need a deliberately designed authorization check before context is
activated. Calling `activate` alone is not authorization. Do not copy a request's
connection or assume its security context is available in the worker.

Process each tenant in its own transaction. After a rollback, a retry must
authorize and establish context again. Schedule work that depends on newly
written data only after the producing transaction commits.

## Tests to add with these extensions

Use real PostgreSQL with the new migrations and the restricted `saas_app`
runtime role. These are requirements for future extensions, not claims that
tasks, slugs, or background jobs are already implemented or tested.

| Scenario | Expected result |
| --- | --- |
| Insert a task for A referencing A's project | Succeeds. |
| Insert or update A's task to reference B's existing project while context is A | The composite foreign key rejects it; assert the constraint failure, not an unrelated validation or RLS error. |
| Use the same slug in A and B | Both succeed; a second identical slug within A fails uniqueness. |
| Submit a job for a tenant its service identity cannot access | Authorization rejects it before tenant data is read or changed. |
| Query protected data in a worker without tenant context | Reads return no rows and inserts are rejected. |
| Reuse a worker thread and pooled connection for A then B, including after rollback and retry | Each operation sees only its authorized tenant; committed audit records match and rolled-back writes leave no audit records. |
