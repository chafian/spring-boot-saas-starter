CREATE TABLE tenants (
    id uuid PRIMARY KEY,
    name varchar(100) NOT NULL CHECK (length(trim(name)) > 0),
    owner_subject varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE memberships (
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    subject varchar(200) NOT NULL,
    role varchar(10) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    PRIMARY KEY (tenant_id, subject)
);
CREATE UNIQUE INDEX one_owner_per_tenant ON memberships(tenant_id) WHERE role = 'OWNER';
CREATE INDEX memberships_subject ON memberships(subject, tenant_id);
CREATE TABLE projects (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    name varchar(100) NOT NULL CHECK (length(trim(name)) > 0),
    description varchar(2000) NOT NULL DEFAULT '',
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX projects_tenant_created ON projects(tenant_id, created_at, id);
CREATE TABLE audit_events (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    actor varchar(200) NOT NULL,
    action varchar(40) NOT NULL,
    resource_id varchar(200) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_tenant_time ON audit_events(tenant_id, occurred_at, id);
ALTER TABLE projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects FORCE ROW LEVEL SECURITY;
CREATE POLICY project_tenant ON projects
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_tenant ON audit_events
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO saas_app;
GRANT SELECT, INSERT ON tenants TO saas_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON memberships, projects TO saas_app;
GRANT SELECT, INSERT ON audit_events TO saas_app;
