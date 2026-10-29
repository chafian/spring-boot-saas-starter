package io.github.chafian.saas.project;

import io.github.chafian.saas.audit.AuditService;
import io.github.chafian.saas.common.ApiException;
import io.github.chafian.saas.tenancy.TenantAccess;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
  private final JdbcClient jdbc;
  private final TenantAccess access;
  private final AuditService audit;

  public ProjectService(JdbcClient jdbc, TenantAccess access, AuditService audit) {
    this.jdbc = jdbc;
    this.access = access;
    this.audit = audit;
  }

  public record Project(
      UUID id,
      UUID tenantId,
      String name,
      String description,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  public record Draft(String name, String description) {}

  public record Revision(Draft draft, long version) {}

  @Transactional
  public Project create(UUID tenantId, String actor, Draft draft) {
    access.requireWriter(tenantId, actor);
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO projects(id, tenant_id, name, description) VALUES (?, ?, ?, ?)")
        .params(id, tenantId, draft.name().strip(), draft.description())
        .update();
    audit.record(tenantId, actor, "PROJECT_CREATED", id.toString());
    return find(tenantId, id);
  }

  @Transactional
  public List<Project> list(UUID tenantId, String actor, int limit, int offset) {
    access.requireMember(tenantId, actor);
    return jdbc.sql(
            "SELECT * FROM projects WHERE tenant_id = ? ORDER BY created_at, id LIMIT ? OFFSET ?")
        .params(tenantId, limit, offset)
        .query(Project.class)
        .list();
  }

  @Transactional
  public Project get(UUID tenantId, String actor, UUID id) {
    access.requireMember(tenantId, actor);
    return find(tenantId, id);
  }

  @Transactional
  public Project update(UUID tenantId, String actor, UUID id, Revision revision) {
    access.requireWriter(tenantId, actor);
    int updated =
        jdbc.sql(
                "UPDATE projects SET name = ?, description = ?, version = version + 1, updated_at = now() WHERE tenant_id = ? AND id = ? AND version = ?")
            .params(
                revision.draft().name().strip(),
                revision.draft().description(),
                tenantId,
                id,
                revision.version())
            .update();
    requireChanged(updated, tenantId, id);
    audit.record(tenantId, actor, "PROJECT_UPDATED", id.toString());
    return find(tenantId, id);
  }

  @Transactional
  public void delete(UUID tenantId, String actor, UUID id, long version) {
    access.requireWriter(tenantId, actor);
    int deleted =
        jdbc.sql("DELETE FROM projects WHERE tenant_id = ? AND id = ? AND version = ?")
            .params(tenantId, id, version)
            .update();
    requireChanged(deleted, tenantId, id);
    audit.record(tenantId, actor, "PROJECT_DELETED", id.toString());
  }

  private void requireChanged(int count, UUID tenantId, UUID id) {
    if (count == 0) {
      find(tenantId, id);
      throw new ApiException(
          HttpStatus.CONFLICT, "Project version changed; reload before retrying");
    }
  }

  private Project find(UUID tenantId, UUID id) {
    return jdbc.sql("SELECT * FROM projects WHERE tenant_id = ? AND id = ?")
        .params(tenantId, id)
        .query(Project.class)
        .optional()
        .orElseThrow(ApiException::notFound);
  }
}
