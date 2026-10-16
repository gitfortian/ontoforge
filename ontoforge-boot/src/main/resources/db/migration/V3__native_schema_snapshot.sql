-- OFIP-0003: local schema snapshots, NOT approved releases or activations.
-- Preserve V1 installation identity and V2 single editable draft unchanged.
CREATE TABLE native_schema_snapshot (
    snapshot_id UUID PRIMARY KEY,
    package_id TEXT NOT NULL,
    source_draft_id UUID NOT NULL REFERENCES native_package_draft(draft_id) ON DELETE RESTRICT,
    source_revision BIGINT NOT NULL CHECK (source_revision > 0),
    idempotency_key UUID NOT NULL,
    candidate_schema_version TEXT NOT NULL CHECK (candidate_schema_version = '0.2.0'),
    canonicalization_profile TEXT NOT NULL CHECK (canonicalization_profile = 'jcs-rfc8785-v1'),
    canonical_payload BYTEA NOT NULL CHECK (octet_length(canonical_payload) BETWEEN 1 AND 262144),
    snapshot_document JSONB NOT NULL CHECK (jsonb_typeof(snapshot_document) = 'object'),
    payload_sha256 CHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT native_snapshot_one_revision UNIQUE (source_draft_id, source_revision),
    CONSTRAINT native_snapshot_one_key UNIQUE (source_draft_id, idempotency_key)
);
CREATE INDEX native_snapshot_package_page ON native_schema_snapshot (package_id,created_at DESC,snapshot_id DESC);

-- Product-level immutability: reject normal UPDATE/DELETE for all application sessions.
-- A database superuser can still disable triggers; this is NOT tamper-proof audit.
CREATE FUNCTION native_snapshot_protect() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'immutable schema snapshot: updates and deletes are forbidden'
      USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER native_snapshot_immutable
    BEFORE UPDATE OR DELETE ON native_schema_snapshot
    FOR EACH ROW EXECUTE FUNCTION native_snapshot_protect();
