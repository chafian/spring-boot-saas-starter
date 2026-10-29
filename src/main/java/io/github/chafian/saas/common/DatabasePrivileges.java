package io.github.chafian.saas.common;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class DatabasePrivileges implements ApplicationRunner {
  private final JdbcClient jdbc;

  public DatabasePrivileges(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void run(ApplicationArguments args) {
    boolean unsafe =
        jdbc.sql(
                """
        SELECT r.rolsuper OR r.rolbypassrls
          OR has_schema_privilege(current_user, 'public', 'CREATE')
          OR (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname IN ('projects', 'audit_events')) <> 2
          OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname IN ('projects', 'audit_events')
              AND (pg_has_role(current_user, c.relowner, 'MEMBER') OR NOT c.relrowsecurity
                   OR NOT c.relforcerowsecurity))
        FROM pg_roles r WHERE r.rolname = current_user
        """)
            .query(Boolean.class)
            .single();
    if (unsafe)
      throw new IllegalStateException("Runtime database role must be restricted and RLS enforced");
  }
}
