package io.github.chafian.saas.tenancy;

import io.github.chafian.saas.common.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TenantAccess {
  private final JdbcClient jdbc;

  public TenantAccess(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Role requireMember(UUID tenantId, String subject) {
    Role role =
        jdbc.sql("SELECT role FROM memberships WHERE tenant_id = ? AND subject = ? FOR SHARE")
            .params(tenantId, subject)
            .query(String.class)
            .optional()
            .map(Role::valueOf)
            .orElseThrow(ApiException::notFound);
    activate(tenantId);
    return role;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireWriter(UUID tenantId, String subject) {
    if (requireMember(tenantId, subject) == Role.MEMBER) throw ApiException.forbidden();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireOwner(UUID tenantId, String subject) {
    // Lock the owner directly: upgrading a shared lock can deadlock concurrent owner requests.
    Role role =
        jdbc.sql("SELECT role FROM memberships WHERE tenant_id = ? AND subject = ? FOR UPDATE")
            .params(tenantId, subject)
            .query(String.class)
            .optional()
            .map(Role::valueOf)
            .orElseThrow(ApiException::notFound);
    if (role != Role.OWNER) throw ApiException.forbidden();
    activate(tenantId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void activate(UUID tenantId) {
    jdbc.sql("SELECT set_config('app.tenant_id', ?, true)")
        .param(tenantId.toString())
        .query(String.class)
        .single();
  }
}
