-- M8.2: forward-only, mode-discriminated LinkBinding metadata.
-- OFIP-0010 M8.2 Bridge-only [VOTE] Accepted at PR #103 #issuecomment-6281584798.
-- Do NOT modify V10 or UPDATE any existing immutable LinkBinding revision/operation.
-- Existing FOREIGN_KEY rows logically receive the constant FOREIGN_KEY default.
-- A single Bridge table (not a third Object Binding) is represented by sourceId,
-- schema, table, two key columns and a frozen structural fingerprint.
-- Important: PostgreSQL CHECK constraints evaluate NULL to UNKNOWN (passes).
-- The entire mapping_mode is NOT NULL, while each mode branch has explicit
-- IS NOT NULL guards to make both branches strictly true or false.

ALTER TABLE runtime_link_binding_revision
    ADD COLUMN mapping_mode TEXT NOT NULL DEFAULT 'FOREIGN_KEY',
    ADD COLUMN bridge_source_id TEXT,
    ADD COLUMN bridge_schema TEXT,
    ADD COLUMN bridge_table TEXT,
    ADD COLUMN bridge_source_fk_column TEXT,
    ADD COLUMN bridge_target_fk_column TEXT,
    ADD COLUMN bridge_schema_fingerprint CHAR(64);

-- Preexisting immutable FOREIGN_KEY rows acquire a discriminator default.
-- This migration neither UPDATEs rows nor changes historic digests or receipts.
ALTER TABLE runtime_link_binding_revision
    ALTER COLUMN fk_owner DROP NOT NULL,
    ALTER COLUMN fk_source_column DROP NOT NULL,
    ALTER COLUMN referenced_identity_column DROP NOT NULL;

-- The original column-level CHECKs on FK-only columns are intentionally kept.
-- Those CHECKs already validate non-NULL FK values; NULL belongs only to BRIDGE
-- as enforced by the following complete mode exclusivity constraint.
ALTER TABLE runtime_link_binding_revision
    ADD CONSTRAINT ck_link_revision_mapping_exclusive CHECK (
        (
            mapping_mode = 'FOREIGN_KEY'
            AND fk_owner IS NOT NULL
            AND fk_source_column IS NOT NULL
            AND referenced_identity_column IS NOT NULL
            AND bridge_source_id IS NULL
            AND bridge_schema IS NULL
            AND bridge_table IS NULL
            AND bridge_source_fk_column IS NULL
            AND bridge_target_fk_column IS NULL
            AND bridge_schema_fingerprint IS NULL
        )
        OR
        (
            mapping_mode = 'BRIDGE_TABLE'
            AND fk_owner IS NULL
            AND fk_source_column IS NULL
            AND referenced_identity_column IS NULL
            AND bridge_source_id IS NOT NULL
            AND length(bridge_source_id) BETWEEN 1 AND 200
            AND bridge_schema IS NOT NULL
            AND bridge_schema = 'public'
            AND bridge_table IS NOT NULL
            AND bridge_table ~ '^[a-z][a-z0-9_]{0,62}$'
            AND bridge_source_fk_column IS NOT NULL
            AND bridge_source_fk_column ~ '^[a-z][a-z0-9_]{0,62}$'
            AND bridge_target_fk_column IS NOT NULL
            AND bridge_target_fk_column ~ '^[a-z][a-z0-9_]{0,62}$'
            AND bridge_source_fk_column <> bridge_target_fk_column
            AND bridge_schema_fingerprint IS NOT NULL
            AND bridge_schema_fingerprint ~ '^[0-9a-f]{64}$'
        )
    );

