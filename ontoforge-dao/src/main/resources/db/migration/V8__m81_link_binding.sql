-- OFIP-0010 Accepted (M8.1 only): immutable single-column FOREIGN_KEY LinkBinding.
-- Never mutate Flyway V1-V7 or the M5 runtime_binding head/revision history.
CREATE TABLE runtime_link_binding (
    link_binding_id UUID PRIMARY KEY,
    package_id TEXT NOT NULL,
    snapshot_id UUID NOT NULL REFERENCES native_schema_snapshot(snapshot_id) ON DELETE RESTRICT,
    link_type_id TEXT NOT NULL CHECK (link_type_id ~ '^[a-z][a-z0-9-]{1,63}$'),
    current_revision BIGINT NOT NULL DEFAULT 0 CHECK (current_revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_runtime_link_binding_type UNIQUE (package_id, snapshot_id, link_type_id)
);

CREATE TABLE runtime_link_binding_revision (
    link_binding_id UUID NOT NULL REFERENCES runtime_link_binding(link_binding_id) ON DELETE RESTRICT,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    snapshot_id UUID NOT NULL REFERENCES native_schema_snapshot(snapshot_id) ON DELETE RESTRICT,
    package_id TEXT NOT NULL,
    link_type_id TEXT NOT NULL,
    model_payload_sha256 CHAR(64) NOT NULL CHECK (model_payload_sha256 ~ '^[0-9a-f]{64}$'),
    source_binding_id UUID NOT NULL,
    source_binding_revision BIGINT NOT NULL,
    source_binding_digest CHAR(64) NOT NULL,
    target_binding_id UUID NOT NULL,
    target_binding_revision BIGINT NOT NULL,
    target_binding_digest CHAR(64) NOT NULL,
    fk_owner TEXT NOT NULL CHECK (fk_owner IN ('SOURCE','TARGET')),
    fk_source_column TEXT NOT NULL CHECK (fk_source_column ~ '^[a-z][a-z0-9_]{0,62}$'),
    referenced_identity_column TEXT NOT NULL CHECK (referenced_identity_column ~ '^[a-z][a-z0-9_]{0,62}$'),
    source_schema_fingerprint CHAR(64) NOT NULL,
    target_schema_fingerprint CHAR(64) NOT NULL,
    cardinality TEXT NOT NULL CHECK (cardinality IN ('ONE_TO_ONE','ONE_TO_MANY','MANY_TO_ONE')),
    quality TEXT NOT NULL CHECK (quality IN ('VALID','VALID_WITH_ORPHANS')),
    checked_rows INTEGER NOT NULL CHECK (checked_rows >= 0 AND checked_rows <= 10000),
    orphan_count INTEGER NOT NULL CHECK (orphan_count >= 0),
    null_fk_count INTEGER NOT NULL CHECK (null_fk_count >= 0),
    digest CHAR(64) NOT NULL CHECK (digest ~ '^[0-9a-f]{64}$'),
    checked_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(link_binding_id, revision),
    CONSTRAINT fk_link_source_binding FOREIGN KEY (source_binding_id, source_binding_revision)
        REFERENCES runtime_binding_revision(binding_id, revision) ON DELETE RESTRICT,
    CONSTRAINT fk_link_target_binding FOREIGN KEY (target_binding_id, target_binding_revision)
        REFERENCES runtime_binding_revision(binding_id, revision) ON DELETE RESTRICT
);

ALTER TABLE runtime_link_binding
    ADD CONSTRAINT fk_current_link_binding_revision
    FOREIGN KEY (link_binding_id, current_revision)
    REFERENCES runtime_link_binding_revision(link_binding_id, revision)
    DEFERRABLE INITIALLY DEFERRED;

-- Same operation UUID and same digest recovers the historical result; different
-- digest must fail, including when the optimistic head has already advanced.
CREATE TABLE runtime_link_binding_operation (
    package_id TEXT NOT NULL,
    idempotency_key UUID NOT NULL,
    link_binding_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    request_digest CHAR(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (package_id, idempotency_key),
    CONSTRAINT fk_link_operation_revision FOREIGN KEY(link_binding_id, revision)
        REFERENCES runtime_link_binding_revision(link_binding_id, revision) ON DELETE RESTRICT
);
CREATE INDEX ix_runtime_link_revision_snapshot ON runtime_link_binding_revision(snapshot_id, link_type_id);
CREATE INDEX ix_runtime_link_revision_endpoints ON runtime_link_binding_revision(source_binding_id, target_binding_id);

CREATE OR REPLACE FUNCTION reject_runtime_link_revision_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'runtime_link_binding_revision rows are immutable';
END;
$$;
CREATE TRIGGER trg_runtime_link_revision_immutable
BEFORE UPDATE OR DELETE ON runtime_link_binding_revision
FOR EACH ROW EXECUTE FUNCTION reject_runtime_link_revision_mutation();

-- Synthetic LOCAL source fixtures used only in M8.1 integration tests and MVP.
-- Referencing FK is intentionally not constrained: orphan handling is a core
-- ontology runtime validation story, not a DDL FK admission shortcut.
CREATE TABLE m8_equipment_source (
    equipment_code TEXT PRIMARY KEY,
    equipment_name TEXT NOT NULL,
    plant TEXT NOT NULL
);
CREATE TABLE m8_inspection_lot_source (
    lot_code TEXT PRIMARY KEY,
    equipment_code TEXT,
    status TEXT NOT NULL,
    measured_at TIMESTAMPTZ NOT NULL
);
INSERT INTO m8_equipment_source VALUES
('EQ-001','Mill A','PLANT-A'),
('EQ-002','Lathe B','PLANT-B'),('EQ-003','Press C','PLANT-A');
INSERT INTO m8_inspection_lot_source VALUES
('LOT-11','EQ-001','PASSED','2026-09-03T01:00:00Z'),
('LOT-12','EQ-001','FAILED','2026-09-03T02:00:00Z'),
('LOT-13',NULL,'PASSED','2026-09-03T03:00:00Z'),
('LOT-14','EQ-UNKNOWN','FAILED','2026-09-03T04:00:00Z'),
('LOT-15','EQ-003','PASSED','2026-09-03T05:00:00Z');

-- Reader privileges remain strictly SELECT-only; app metadata is never granted.
DO $$
DECLARE target_schema TEXT := current_schema();
BEGIN
    EXECUTE format('GRANT SELECT ON TABLE %I.m8_equipment_source, %I.m8_inspection_lot_source TO ontoforge_binding_reader',
        target_schema, target_schema);
    EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE %I.m8_equipment_source, %I.m8_inspection_lot_source FROM ontoforge_binding_reader',
        target_schema, target_schema);
END $$;
