package io.github.chafian.saas.audit;

import io.github.chafian.saas.tenancy.TenantAccess;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
  private final JdbcClient jdbc;
  private final TenantAccess access;

  public AuditService(JdbcClient jdbc, TenantAccess access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  public record Event(
      UUID id, String actor, String action, String resourceId, Instant occurredAt) {}

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(UUID tenantId, String actor, String action, String resourceId) {
    jdbc.sql(
            "INSERT INTO audit_events(id, tenant_id, actor, action, resource_id) VALUES (?, ?, ?, ?, ?)")
        .params(UUID.randomUUID(), tenantId, actor, action, resourceId)
        .update();
  }

  @Transactional
  public List<Event> list(UUID tenantId, String actor, int limit, int offset) {
    access.requireWriter(tenantId, actor);
    return jdbc.sql(
            "SELECT id, actor, action, resource_id, occurred_at FROM audit_events WHERE tenant_id = ? ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?")
        .params(tenantId, limit, offset)
        .query(Event.class)
        .list();
  }
}
