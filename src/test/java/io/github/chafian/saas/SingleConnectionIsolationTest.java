package io.github.chafian.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.chafian.saas.audit.AuditService;
import io.github.chafian.saas.project.ProjectService;
import io.github.chafian.saas.tenancy.TenantAccess;
import io.github.chafian.saas.tenancy.TenantService;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Exercises service transactions and RLS; HTTP/JWT coverage stays in StarterIntegrationTest. */
@SpringBootTest
class SingleConnectionIsolationTest {
  static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17-alpine");

  static {
    DATABASE.start();
    try (var connection =
            DriverManager.getConnection(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE ROLE saas_migrator LOGIN PASSWORD 'test-migration' NOSUPERUSER NOBYPASSRLS");
      statement.execute(
          "CREATE ROLE saas_app LOGIN PASSWORD 'test-runtime' NOSUPERUSER NOBYPASSRLS");
      statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
      statement.execute("ALTER SCHEMA public OWNER TO saas_migrator");
    } catch (SQLException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "saas_app");
    registry.add("spring.datasource.password", () -> "test-runtime");
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    registry.add("spring.flyway.url", DATABASE::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "saas_migrator");
    registry.add("spring.flyway.password", () -> "test-migration");
    registry.add("app.security.issuer", () -> "https://issuer.example.test/realms/saas");
    registry.add("app.security.jwk-set-uri", () -> "https://issuer.example.test/jwks");
    registry.add("app.security.audience", () -> "saas-api");
  }

  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired TenantService tenants;
  @Autowired TenantAccess access;
  @Autowired ProjectService projects;
  @Autowired AuditService audit;
  int backendPid;

  record Workspace(UUID tenant, String owner, UUID project) {}

  @BeforeEach
  void rememberRuntimeConnection() {
    backendPid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("saas_app");
    assertThat(
            jdbc.sql("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query(Boolean.class)
                .single())
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void alternatingTenantsStayIsolatedAfterCommitOrExceptionRollback(boolean rollback) {
    Workspace first = createWorkspace("A");
    Workspace second = createWorkspace("B");
    assertMissingContextFailsClosed(first);

    // Return the connection to the pool between operations, including failed transactions.
    reviseProject(first, 0, rollback);
    assertMissingContextFailsClosed(first);
    reviseProject(second, 0, rollback);
    assertMissingContextFailsClosed(second);

    long version = rollback ? 0 : 1;
    assertTenantBoundary(first, second, version);
    assertTenantBoundary(second, first, version);

    // Reuse the same connection for successful work after the earlier commits/rollbacks.
    reviseProject(first, version, false);
    assertMissingContextFailsClosed(first);
    reviseProject(second, version, false);
    assertMissingContextFailsClosed(second);
    assertTenantBoundary(first, second, version + 1);
    assertTenantBoundary(second, first, version + 1);
  }

  private Workspace createWorkspace(String name) {
    String owner = "owner-" + UUID.randomUUID();
    UUID tenant = tenants.create(name, owner).id();
    UUID project = projects.create(tenant, owner, new ProjectService.Draft("Version 0", "")).id();
    assertSameConnection();
    return new Workspace(tenant, owner, project);
  }

  private void reviseProject(Workspace workspace, long version, boolean rollback) {
    Runnable revision =
        () ->
            transaction()
                .executeWithoutResult(
                    status -> {
                      assertSameConnection();
                      projects.update(
                          workspace.tenant(),
                          workspace.owner(),
                          workspace.project(),
                          new ProjectService.Revision(
                              new ProjectService.Draft("Version " + (version + 1), ""), version));
                      if (rollback) throw new IllegalStateException("Simulated request failure");
                    });
    if (rollback) {
      assertThatExceptionOfType(IllegalStateException.class)
          .isThrownBy(revision::run)
          .withMessage("Simulated request failure");
    } else {
      revision.run();
    }
  }

  private void assertTenantBoundary(Workspace own, Workspace other, long version) {
    var visibleProjects = projects.list(own.tenant(), own.owner(), 100, 0);
    assertThat(visibleProjects)
        .extracting(ProjectService.Project::id)
        .containsExactly(own.project());
    assertThat(visibleProjects.getFirst().version()).isEqualTo(version);
    assertThat(visibleProjects.getFirst().name()).isEqualTo("Version " + version);
    var events = audit.list(own.tenant(), own.owner(), 100, 0);
    assertThat(events).hasSize((int) version + 2);
    assertThat(events).extracting(AuditService.Event::actor).containsOnly(own.owner());
    assertThat(events)
        .extracting(AuditService.Event::resourceId)
        .containsOnly(own.tenant().toString(), own.project().toString());

    transaction()
        .executeWithoutResult(
            status -> {
              assertSameConnection();
              access.requireMember(own.tenant(), own.owner());
              // No tenant predicates: these assertions must depend on PostgreSQL RLS.
              assertThat(jdbc.sql("SELECT id FROM projects").query(UUID.class).list())
                  .containsExactly(own.project());
              assertThat(jdbc.sql("SELECT tenant_id FROM audit_events").query(UUID.class).list())
                  .hasSize((int) version + 2)
                  .containsOnly(own.tenant());
              assertThat(
                      jdbc.sql("UPDATE projects SET name = 'Foreign write' WHERE id = ?")
                          .param(other.project())
                          .update())
                  .isZero();
              assertThat(
                      jdbc.sql("DELETE FROM projects WHERE id = ?").param(other.project()).update())
                  .isZero();
            });
    for (String table : List.of("projects", "audit_events")) {
      assertInsertDenied(table, own, other.tenant());
    }
    assertMissingContextFailsClosed(own);
  }

  private void assertMissingContextFailsClosed(Workspace workspace) {
    assertSameConnection();
    assertThat(jdbc.sql("SELECT id FROM projects").query(UUID.class).list()).isEmpty();
    assertThat(jdbc.sql("SELECT id FROM audit_events").query(UUID.class).list()).isEmpty();
    assertThat(jdbc.sql("UPDATE projects SET name = 'No context'").update()).isZero();
    assertThat(jdbc.sql("DELETE FROM projects").update()).isZero();
    for (String table : List.of("projects", "audit_events")) {
      assertInsertDenied(table, null, workspace.tenant());
    }
  }

  private void assertInsertDenied(String table, Workspace context, UUID targetTenant) {
    assertThatExceptionOfType(DataAccessException.class)
        .isThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          assertSameConnection();
                          if (context != null)
                            access.requireMember(context.tenant(), context.owner());
                          // Use an existing tenant so an FK failure cannot masquerade as RLS
                          // denial.
                          if (table.equals("projects")) {
                            jdbc.sql(
                                    "INSERT INTO projects(id, tenant_id, name) VALUES (?, ?, 'Denied')")
                                .params(UUID.randomUUID(), targetTenant)
                                .update();
                          } else {
                            jdbc.sql(
                                    "INSERT INTO audit_events(id, tenant_id, actor, action, resource_id) VALUES (?, ?, 'test', 'DENIED', 'test')")
                                .params(UUID.randomUUID(), targetTenant)
                                .update();
                          }
                        }))
        .satisfies(
            error -> {
              assertThat(error.getMostSpecificCause()).isInstanceOf(SQLException.class);
              assertThat(((SQLException) error.getMostSpecificCause()).getSQLState())
                  .isEqualTo("42501");
            });
    assertSameConnection();
  }

  private void assertSameConnection() {
    assertThat(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single())
        .isEqualTo(backendPid);
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(transactionManager);
  }
}
