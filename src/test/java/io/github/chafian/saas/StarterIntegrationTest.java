package io.github.chafian.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.chafian.saas.common.DatabasePrivileges;
import io.github.chafian.saas.project.ProjectService;
import io.github.chafian.saas.tenancy.TenantAccess;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StarterIntegrationTest {
  static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17-alpine");
  static final RSAKey SIGNING_KEY;
  static final HttpServer JWKS;
  static final String ISSUER = "https://issuer.example.test/realms/saas";
  static final HttpClient HTTP = HttpClient.newHttpClient();

  static {
    try {
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
      }
      SIGNING_KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
      JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] keys =
          new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      JWKS.createContext(
          "/jwks",
          exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, keys.length);
            try (var body = exchange.getResponseBody()) {
              body.write(keys);
            }
          });
      JWKS.start();
    } catch (Exception exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "saas_app");
    registry.add("spring.datasource.password", () -> "test-runtime");
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    registry.add("spring.flyway.url", DATABASE::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "saas_migrator");
    registry.add("spring.flyway.password", () -> "test-migration");
    registry.add("app.security.issuer", () -> ISSUER);
    registry.add(
        "app.security.jwk-set-uri",
        () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    registry.add("app.security.audience", () -> "saas-api");
  }

  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcClient jdbc;
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired TenantAccess access;
  @Autowired ProjectService projects;
  String owner;
  String ownerToken;
  UUID tenant;

  @BeforeEach
  void workspace() throws Exception {
    owner = "owner-" + UUID.randomUUID();
    ownerToken = token(owner, "valid");
    tenant =
        UUID.fromString(
            call("POST", "/api/v1/tenants", ownerToken, Map.of("name", "Workspace"), 201)
                .get("id")
                .asText());
  }

  @AfterAll
  static void stopJwks() {
    JWKS.stop(0);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "expired",
        "wrong-issuer",
        "wrong-audience",
        "missing-audience",
        "wrong-key",
        "missing-subject",
        "missing-expiry",
        "future",
        "malformed"
      })
  void untrustedTokenCannotEnterApi(String variant) throws Exception {
    call("GET", "/api/v1/tenants", token(owner, variant), null, 401);
  }

  @Test
  void anonymousApiIsDeniedWhileHealthIsPublic() throws Exception {
    call("GET", "/api/v1/tenants", null, null, 401);
    assertThat(call("GET", "/actuator/health/readiness", null, null, 200).get("status").asText())
        .isEqualTo("UP");
  }

  @Test
  void tenantListOnlyShowsMembershipsOfAuthenticatedSubject() throws Exception {
    String outsider = token("outsider-" + UUID.randomUUID(), "valid");
    assertThat(call("GET", "/api/v1/tenants", outsider, null, 200).size()).isZero();
    JsonNode tenants = call("GET", "/api/v1/tenants", ownerToken, null, 200);
    assertThat(tenants.size()).isEqualTo(1);
    assertThat(tenants.get(0).get("id").asText()).isEqualTo(tenant.toString());
    assertThat(call("GET", path(""), ownerToken, null, 200).get("role").asText())
        .isEqualTo("OWNER");
    call("GET", path(""), outsider, null, 404);
  }

  @Test
  void tenantPathAndForeignProjectIdCannotCrossIsolationBoundary() throws Exception {
    JsonNode project = createProject();
    String stranger = token("stranger-" + UUID.randomUUID(), "valid");
    call("GET", path("/projects"), stranger, null, 404);
    call("GET", path("/members"), stranger, null, 404);
    call("GET", path("/audit-events"), stranger, null, 404);
    UUID second =
        UUID.fromString(
            call("POST", "/api/v1/tenants", ownerToken, Map.of("name", "Second"), 201)
                .get("id")
                .asText());
    String wrongPath = "/api/v1/tenants/" + second + "/projects/" + project.get("id").asText();
    call("GET", wrongPath, ownerToken, null, 404);
    call(
        "PUT",
        wrongPath,
        ownerToken,
        Map.of("name", "Wrong", "description", "", "version", 0),
        404);
    call("DELETE", wrongPath + "?version=0", ownerToken, null, 404);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ADMIN", "MEMBER"})
  void rolesApplyPerTenantAndCannotManageMemberships(String role) throws Exception {
    String subject = "member-" + UUID.randomUUID();
    String memberToken = token(subject, "valid");
    setMember(subject, role);
    call("GET", path("/projects"), memberToken, null, 200);
    call("PUT", path("/members"), memberToken, Map.of("subject", "someone", "role", "ADMIN"), 403);
    call("DELETE", path("/members?subject=" + subject), memberToken, null, 403);
    int writeStatus = role.equals("ADMIN") ? 201 : 403;
    call(
        "POST",
        path("/projects"),
        memberToken,
        Map.of("name", "Example", "description", ""),
        writeStatus);
    call("GET", path("/audit-events"), memberToken, null, role.equals("ADMIN") ? 200 : 403);
    String projectUrl = path("/projects/" + createProject().get("id").asText());
    call(
        "PUT",
        projectUrl,
        memberToken,
        Map.of("name", "Changed", "description", "", "version", 0),
        role.equals("ADMIN") ? 200 : 403);
    call("DELETE", projectUrl + "?version=1", memberToken, null, role.equals("ADMIN") ? 204 : 403);
  }

  @Test
  void roleDowngradeAppliesWithoutRefreshingToken() throws Exception {
    String subject = "downgraded-" + UUID.randomUUID();
    String memberToken = token(subject, "valid");
    setMember(subject, "ADMIN");
    call("POST", path("/projects"), memberToken, Map.of("name", "Allowed", "description", ""), 201);
    setMember(subject, "MEMBER");
    call("POST", path("/projects"), memberToken, Map.of("name", "Denied", "description", ""), 403);
    call("GET", path("/projects"), memberToken, null, 200);
  }

  @Test
  void concurrentMembershipWritesSerializeWithoutLockUpgradeDeadlock() throws Exception {
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var attempts =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  index ->
                      executor.submit(
                          () -> {
                            start.await();
                            return send(
                                    "PUT",
                                    path("/members"),
                                    ownerToken,
                                    Map.of("subject", "concurrent-" + index, "role", "MEMBER"))
                                .statusCode();
                          }))
              .toList();
      start.countDown();
      assertThat(List.of(attempts.get(0).get(), attempts.get(1).get())).containsOnly(204);
    }
    assertThat(call("GET", path("/members"), ownerToken, null, 200).size()).isEqualTo(3);
  }

  @Test
  void membershipRevocationTakesEffectWithExistingToken() throws Exception {
    String subject = "revoked-" + UUID.randomUUID();
    String memberToken = token(subject, "valid");
    setMember(subject, "ADMIN");
    call("GET", path("/projects"), memberToken, null, 200);
    call("DELETE", path("/members?subject=" + subject), ownerToken, null, 204);
    call("GET", path("/projects"), memberToken, null, 404);
    call("POST", path("/projects"), memberToken, Map.of("name", "Denied", "description", ""), 404);
  }

  @Test
  void ownerCannotBeRemovedDemotedOrDuplicated() throws Exception {
    call("DELETE", path("/members?subject=" + owner), ownerToken, null, 409);
    call("PUT", path("/members"), ownerToken, Map.of("subject", owner, "role", "MEMBER"), 409);
    call(
        "PUT",
        path("/members"),
        ownerToken,
        Map.of("subject", "second-owner", "role", "OWNER"),
        400);
    assertThat(call("GET", path("/members"), ownerToken, null, 200).size()).isEqualTo(1);
  }

  @Test
  void projectLifecycleDetectsStaleUpdatesAndRecordsCommittedEvents() throws Exception {
    JsonNode project = createProject();
    String url = path("/projects/" + project.get("id").asText());
    JsonNode updated =
        call(
            "PUT",
            url,
            ownerToken,
            Map.of("name", "Renamed", "description", "Updated", "version", 0),
            200);
    assertThat(updated.get("version").asLong()).isEqualTo(1);
    assertThat(updated.get("name").asText()).isEqualTo("Renamed");
    call("PUT", url, ownerToken, Map.of("name", "Stale", "description", "", "version", 0), 409);
    call("DELETE", url + "?version=0", ownerToken, null, 409);
    call("DELETE", url + "?version=1", ownerToken, null, 204);
    call("GET", url, ownerToken, null, 404);
    JsonNode events = call("GET", path("/audit-events"), ownerToken, null, 200);
    assertThat(events.size()).isEqualTo(4);
    assertThat(events.toString()).contains("PROJECT_CREATED", "PROJECT_UPDATED", "PROJECT_DELETED");
  }

  @Test
  void simultaneousUpdatesHaveExactlyOneWinner() throws Exception {
    String url = path("/projects/" + createProject().get("id").asText());
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var attempts =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  index ->
                      executor.submit(
                          () -> {
                            start.await();
                            return send(
                                    "PUT",
                                    url,
                                    ownerToken,
                                    Map.of(
                                        "name", "Update " + index, "description", "", "version", 0))
                                .statusCode();
                          }))
              .toList();
      start.countDown();
      assertThat(List.of(attempts.get(0).get(), attempts.get(1).get()))
          .containsExactlyInAnyOrder(200, 409);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void blankProjectNameDoesNotWriteData(String name) throws Exception {
    call("POST", path("/projects"), ownerToken, Map.of("name", name, "description", ""), 400);
    assertThat(call("GET", path("/projects"), ownerToken, null, 200).size()).isZero();
  }

  @Test
  void massAssignmentAndUnboundedPaginationAreRejected() throws Exception {
    call(
        "POST",
        path("/projects"),
        ownerToken,
        Map.of("name", "Example", "description", "", "tenantId", UUID.randomUUID().toString()),
        400);
    call("GET", path("/projects?limit=101"), ownerToken, null, 400);
    call("GET", path("/projects?offset=-1"), ownerToken, null, 400);
    call(
        "PUT",
        path("/projects/" + UUID.randomUUID()),
        ownerToken,
        Map.of("name", "Example", "description", ""),
        400);
  }

  @Test
  void paginationIsBoundedAndStableAcrossPages() throws Exception {
    createProject();
    createProject();
    JsonNode first = call("GET", path("/projects?limit=1&offset=0"), ownerToken, null, 200);
    JsonNode second = call("GET", path("/projects?limit=1&offset=1"), ownerToken, null, 200);
    assertThat(first.size()).isEqualTo(1);
    assertThat(second.size()).isEqualTo(1);
    assertThat(first.get(0).get("id").asText()).isNotEqualTo(second.get(0).get("id").asText());
  }

  @Test
  void rowSecurityHidesOtherTenantsEvenWithoutSqlPredicate() throws Exception {
    JsonNode project = createProject();
    UUID second =
        UUID.fromString(
            call("POST", "/api/v1/tenants", ownerToken, Map.of("name", "Second"), 201)
                .get("id")
                .asText());
    call(
        "POST",
        "/api/v1/tenants/" + second + "/projects",
        ownerToken,
        Map.of("name", "Other", "description", ""),
        201);
    transaction()
        .executeWithoutResult(
            status -> {
              access.requireMember(tenant, owner);
              assertThat(jdbc.sql("SELECT id FROM projects").query(UUID.class).list())
                  .containsExactly(UUID.fromString(project.get("id").asText()));
              assertThat(
                      jdbc.sql("SELECT DISTINCT tenant_id FROM audit_events")
                          .query(UUID.class)
                          .list())
                  .containsExactly(tenant);
            });
    assertThat(jdbc.sql("SELECT count(*) FROM projects").query(Integer.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM audit_events").query(Integer.class).single())
        .isZero();
  }

  @Test
  void rowSecurityRejectsForgedTenantWrites() {
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          access.requireMember(tenant, owner);
                          jdbc.sql(
                                  "INSERT INTO projects(id, tenant_id, name) VALUES (?, ?, 'Wrong tenant')")
                              .params(UUID.randomUUID(), UUID.randomUUID())
                              .update();
                        }))
        .isInstanceOf(DataAccessException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"commit", "rollback"})
  void transactionCompletionClearsContextOnTheSamePhysicalConnection(String completion)
      throws Exception {
    createProject();
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SELECT set_config('app.tenant_id', '" + tenant + "', true)");
      try (var rows = statement.executeQuery("SELECT count(*) FROM projects")) {
        rows.next();
        assertThat(rows.getInt(1)).isEqualTo(1);
      }
      if (completion.equals("commit")) connection.commit();
      else connection.rollback();
      try (var rows = statement.executeQuery("SELECT count(*) FROM projects")) {
        rows.next();
        assertThat(rows.getInt(1)).isZero();
      }
      connection.commit();
    }
  }

  @Test
  void projectAndAuditRollbackTogether() throws Exception {
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          projects.create(tenant, owner, new ProjectService.Draft("Rollback", ""));
                          throw new IllegalStateException("Abort transaction");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(call("GET", path("/projects"), ownerToken, null, 200).size()).isZero();
    assertThat(call("GET", path("/audit-events"), ownerToken, null, 200).size()).isEqualTo(1);
  }

  @Test
  void runtimeCannotEraseAuditOrCreateTables() {
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_events").update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.sql("CREATE TABLE unwanted(id int)").update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.sql("TRUNCATE projects").update())
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void privilegeGuardRejectsSuperuserConfiguration() {
    var admin =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    var guard = new DatabasePrivileges(JdbcClient.create(admin));
    assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class);
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(transactionManager);
  }

  private String path(String suffix) {
    return "/api/v1/tenants/" + tenant + suffix;
  }

  private JsonNode createProject() throws Exception {
    return call(
        "POST",
        path("/projects"),
        ownerToken,
        Map.of("name", "Example", "description", "Starter project"),
        201);
  }

  private void setMember(String subject, String role) throws Exception {
    call("PUT", path("/members"), ownerToken, Map.of("subject", subject, "role", role), 204);
  }

  private JsonNode call(String method, String path, String token, Object body, int expected)
      throws Exception {
    HttpResponse<String> response = send(method, path, token, body);
    assertThat(response.statusCode())
        .as("%s %s: %s", method, path, response.body())
        .isEqualTo(expected);
    return response.body().isEmpty() ? null : json.readTree(response.body());
  }

  private HttpResponse<String> send(String method, String path, String token, Object body)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    builder.header("Content-Type", "application/json");
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String token(String subject, String variant) throws Exception {
    if (variant.equals("malformed")) return "invalid.token.value";
    var claims =
        new JWTClaimsSet.Builder()
            .subject(variant.equals("missing-subject") ? null : subject)
            .issuer(variant.equals("wrong-issuer") ? "https://wrong.example.test" : ISSUER)
            .audience(
                variant.equals("missing-audience")
                    ? null
                    : variant.equals("wrong-audience") ? "other-api" : "saas-api")
            .issueTime(Date.from(Instant.now()));
    if (!variant.equals("missing-expiry"))
      claims.expirationTime(
          Date.from(Instant.now().plusSeconds(variant.equals("expired") ? -120 : 300)));
    if (variant.equals("future")) claims.notBeforeTime(Date.from(Instant.now().plusSeconds(180)));
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims.build());
    jwt.sign(
        new RSASSASigner(
            variant.equals("wrong-key") ? new RSAKeyGenerator(2048).generate() : SIGNING_KEY));
    return jwt.serialize();
  }
}
