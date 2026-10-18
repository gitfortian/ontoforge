-- M5 generic, immutable ObjectType-to-source binding revisions.
-- The supplier fixture is a synthetic local sandbox source, not a production connector.
CREATE TABLE runtime_binding (
    binding_id UUID PRIMARY KEY,
    package_id TEXT NOT NULL,
    object_type_id TEXT NOT NULL,
    identity_domain_id UUID NOT NULL,
    current_revision BIGINT NOT NULL DEFAULT 0 CHECK (current_revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_runtime_binding_package_type UNIQUE (package_id, object_type_id)
);

CREATE TABLE runtime_binding_revision (
    binding_id UUID NOT NULL REFERENCES runtime_binding(binding_id) ON DELETE RESTRICT,
    revision BIGINT NOT NULL CHECK (revision > 0),
    snapshot_id UUID NOT NULL REFERENCES native_schema_snapshot(snapshot_id) ON DELETE RESTRICT,
    payload_sha256 CHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    source_id TEXT NOT NULL CHECK (source_id = 'local-pg-sandbox'),
    source_schema TEXT NOT NULL CHECK (source_schema = 'public'),
    source_table TEXT NOT NULL CHECK (source_table ~ '^[a-z][a-z0-9_]{0,62}$'),
    source_schema_fingerprint CHAR(64) NOT NULL CHECK (source_schema_fingerprint ~ '^[0-9a-f]{64}$'),
    source_key TEXT NOT NULL CHECK (source_key ~ '^[a-z][a-z0-9_]{0,62}$'),
    identity_domain_id UUID NOT NULL,
    field_mappings JSONB NOT NULL CHECK (jsonb_typeof(field_mappings) = 'array'),
    digest CHAR(64) NOT NULL CHECK (digest ~ '^[0-9a-f]{64}$'),
    digest_profile TEXT NOT NULL CHECK (digest_profile = 'jcs-rfc8785-v1'),
    validation_status TEXT NOT NULL CHECK (validation_status IN ('VALID', 'INVALID')),
    checked_at TIMESTAMPTZ NOT NULL,
    checked_rows INTEGER NOT NULL CHECK (checked_rows >= 0 AND checked_rows <= 10000),
    validation_issues JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(validation_issues) = 'array'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (binding_id, revision)
);

ALTER TABLE runtime_binding
    ADD CONSTRAINT fk_runtime_binding_current_revision
    FOREIGN KEY (binding_id, current_revision)
    REFERENCES runtime_binding_revision(binding_id, revision)
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX ix_runtime_binding_revision_snapshot
    ON runtime_binding_revision(snapshot_id, payload_sha256);
CREATE INDEX ix_runtime_binding_revision_source
    ON runtime_binding_revision(source_id, source_schema, source_table);

CREATE OR REPLACE FUNCTION reject_runtime_binding_revision_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'runtime_binding_revision rows are immutable';
END;
$$;

CREATE TRIGGER trg_runtime_binding_revision_immutable
BEFORE UPDATE OR DELETE ON runtime_binding_revision
FOR EACH ROW EXECUTE FUNCTION reject_runtime_binding_revision_mutation();

-- Synthetic, typed source data used by M5 local PostgreSQL acceptance.
CREATE TABLE m5_supplier_source (
    vendor_code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    rating NUMERIC(4,2),
    active BOOLEAN NOT NULL DEFAULT true,
    onboarded_at TIMESTAMPTZ,
    category TEXT
);

INSERT INTO m5_supplier_source(vendor_code, name, rating, active, onboarded_at, category) VALUES
('SUP-1001', 'Northwind Components', 4.75, true,  '2026-01-15T09:00:00Z', 'ELECTRONICS'),
('SUP-1002', 'ForgeWorks Supply',    4.20, true,  '2026-02-10T10:30:00Z', 'METAL'),
('SUP-1003', 'Harbor Industrial',   3.90, true,  '2026-02-25T12:00:00Z', 'LOGISTICS'),
('SUP-1004', 'Precision Materials', 4.95, true,  '2026-03-03T08:15:00Z', 'METAL'),
('SUP-1005', 'Atlas Fasteners',     4.10, false, '2026-03-20T14:00:00Z', 'HARDWARE');

-- Group role contains only read privileges for the explicit M5 fixture.
-- A separate LOGIN role is provisioned by scripts/provision-m5-source-reader.ps1.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ontoforge_binding_reader') THEN
        CREATE ROLE ontoforge_binding_reader NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END $$;
GRANT USAGE ON SCHEMA public TO ontoforge_binding_reader;
GRANT SELECT ON TABLE public.m5_supplier_source TO ontoforge_binding_reader;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE public.m5_supplier_source FROM ontoforge_binding_reader;
