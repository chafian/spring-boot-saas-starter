package io.github.chafian.saas.tenancy;

import io.github.chafian.saas.audit.AuditService;
import io.github.chafian.saas.common.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {
  private final JdbcClient jdbc;
  private final TenantAccess access;
  private final AuditService audit;

  public TenantService(JdbcClient jdbc, TenantAccess access, AuditService audit) {
    this.jdbc = jdbc;
    this.access = access;
    this.audit = audit;
  }

  public record Tenant(UUID id, String name, Role role) {}

  public record Membership(String subject, Role role) {}

  @Transactional
  public Tenant create(String name, String subject) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO tenants(id, name, owner_subject) VALUES (?, ?, ?)")
        .params(id, name.strip(), subject)
        .update();
    jdbc.sql("INSERT INTO memberships(tenant_id, subject, role) VALUES (?, ?, 'OWNER')")
        .params(id, subject)
        .update();
    access.activate(id);
    audit.record(id, subject, "TENANT_CREATED", id.toString());
    return new Tenant(id, name.strip(), Role.OWNER);
  }

  @Transactional(readOnly = true)
  public List<Tenant> list(String subject, int limit, int offset) {
    return jdbc.sql(
            "SELECT t.id, t.name, m.role FROM tenants t JOIN memberships m ON m.tenant_id = t.id WHERE m.subject = ? ORDER BY t.created_at, t.id LIMIT ? OFFSET ?")
        .params(subject, limit, offset)
        .query(Tenant.class)
        .list();
  }

  @Transactional
  public Tenant get(UUID tenantId, String subject) {
    Role role = access.requireMember(tenantId, subject);
    return jdbc.sql("SELECT id, name, ? AS role FROM tenants WHERE id = ?")
        .params(role.name(), tenantId)
        .query(Tenant.class)
        .single();
  }

  @Transactional
  public List<Membership> members(UUID tenantId, String subject, int limit, int offset) {
    access.requireMember(tenantId, subject);
    return jdbc.sql(
            "SELECT subject, role FROM memberships WHERE tenant_id = ? ORDER BY subject LIMIT ? OFFSET ?")
        .params(tenantId, limit, offset)
        .query(Membership.class)
        .list();
  }

  @Transactional
  public void setMember(UUID tenantId, String actor, Membership member) {
    access.requireOwner(tenantId, actor);
    protectOwner(tenantId, member.subject());
    if (member.role() == Role.OWNER)
      throw new ApiException(HttpStatus.BAD_REQUEST, "Ownership transfer is not supported");
    jdbc.sql(
            "INSERT INTO memberships(tenant_id, subject, role) VALUES (?, ?, ?) ON CONFLICT (tenant_id, subject) DO UPDATE SET role = EXCLUDED.role")
        .params(tenantId, member.subject(), member.role().name())
        .update();
    audit.record(tenantId, actor, "MEMBERSHIP_SET_" + member.role().name(), member.subject());
  }

  @Transactional
  public void removeMember(UUID tenantId, String actor, String subject) {
    access.requireOwner(tenantId, actor);
    protectOwner(tenantId, subject);
    int deleted =
        jdbc.sql("DELETE FROM memberships WHERE tenant_id = ? AND subject = ?")
            .params(tenantId, subject)
            .update();
    if (deleted == 0) throw ApiException.notFound();
    audit.record(tenantId, actor, "MEMBERSHIP_REMOVED", subject);
  }

  private void protectOwner(UUID tenantId, String subject) {
    String owner =
        jdbc.sql("SELECT owner_subject FROM tenants WHERE id = ?")
            .param(tenantId)
            .query(String.class)
            .single();
    if (owner.equals(subject))
      throw new ApiException(HttpStatus.CONFLICT, "The tenant owner cannot be changed or removed");
  }
}
