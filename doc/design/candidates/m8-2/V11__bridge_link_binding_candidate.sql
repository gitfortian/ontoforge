-- M8.2 PRE-IMPLEMENTATION CANDIDATE ONLY (doc/design; NOT A FLYWAY MIGRATION)
-- Requires separate M8.2 OFIP-0010 [VOTE] Accepted before promoting to DAO migrations.
-- Based on main Flyway V10 at 2026-10-20. Refresh version slot against main
-- before implementation. V10 revisions and operation receipts must remain unchanged.
--
-- This additive DDL is meant to prove that FOREIGN_KEY and BRIDGE_TABLE are
-- *mutually exclusive*. No fake FK placeholder is acceptable for Bridge.
--
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

-- Preexisting immutable FOREIGN_KEY rows acquire only a virtual constant default.
-- This operation does not UPDATE rows / recompute content_digest / rewrite receipt.
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

-- Candidate verification, performed only in a disposable PG17 transaction:
-- 1. migrate existing V1..V10 test db; snapshot all existing revision/operation
--    key + payload + digest bytes before applying the NEW forward migration;
-- 2. apply this script in a transaction; verify historic FK rows expose the new
--    constant discriminator but retained prior FK fields and content_digest;
-- 3. save legal BRIDGE_TABLE revision using actual existing snapshot and M5
--    binding refs (not fabricated FKs); verify the old immutability triggers;
-- 4. reject: Bridge with FK-only field, FK with Bridge field, unknown mode,
--    Bridge missing fingerprint, duplicate source/target FK column, malformed
--    source table or fingerprint, and writes to old immutable revisions;
-- 5. compare old runtime_link_binding_operation receipts byte-for-byte and
--    confirm old M8.1 API wire format; never modify V10 or edit old revisions.
-- 6. DDL CHECK cannot enforce sourceId allowlist, PG SELECT permission,
--    schema fingerprint freshness, frozen LinkType M:N, or sourceKey type match.
--    These are mandatory runtime validations and integration tests.
