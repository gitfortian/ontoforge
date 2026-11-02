\set ON_ERROR_STOP on
\echo 'M8.2 review-only: isolated PostgreSQL 17 V10 -> Bridge candidate (not Flyway)'

-- Deliberately minimal parent fixtures. Actual V10 DDL and candidate DDL are
-- executed unchanged. This is an isolated compatibility proof, not an app DB.
CREATE TABLE native_schema_snapshot(snapshot_id UUID PRIMARY KEY);
CREATE TABLE runtime_binding_revision (
  binding_id UUID NOT NULL,
  revision BIGINT NOT NULL,
  PRIMARY KEY(binding_id,revision)
);

\i ontoforge-dao/src/main/resources/db/migration/V10__local_link_binding_revisions.sql

BEGIN;
INSERT INTO native_schema_snapshot VALUES ('10000000-0000-0000-0000-000000000001');
INSERT INTO runtime_binding_revision VALUES
 ('20000000-0000-0000-0000-000000000001',2),
 ('20000000-0000-0000-0000-000000000002',3);
INSERT INTO runtime_link_binding(link_binding_id,package_id,snapshot_id,link_type_id,current_revision)
 VALUES ('30000000-0000-0000-0000-000000000001','demo-package',
         '10000000-0000-0000-0000-000000000001','fk-link',1);
INSERT INTO runtime_link_binding_revision (
    link_binding_id,revision,snapshot_id,model_payload_sha256,link_type_hash,
    source_binding_id,source_binding_revision,source_binding_digest,
    target_binding_id,target_binding_revision,target_binding_digest,
    fk_owner,fk_source_column,referenced_identity_column,
    source_schema_fingerprint,target_schema_fingerprint,validation_scope,
    validation_quality,validation_status,checked_at,content_digest
) VALUES (
    '30000000-0000-0000-0000-000000000001',1,
    '10000000-0000-0000-0000-000000000001',repeat('a',64),repeat('b',64),
    '20000000-0000-0000-0000-000000000001',2,repeat('c',64),
    '20000000-0000-0000-0000-000000000002',3,repeat('d',64),
    'SOURCE','equipment_code','equipment_code',
    repeat('e',64),repeat('f',64),'{"coverage":"FULL"}'::jsonb,
    '{"matchedRows":1}'::jsonb,'VALID',now(),repeat('1',64)
);
INSERT INTO runtime_link_binding_operation (
    package_id,link_binding_id,idempotency_key,operation_type,request_digest,committed_revision
) VALUES (
    'demo-package','30000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000001','CREATE',repeat('2',64),1
);
COMMIT;

CREATE TEMP TABLE review_baseline_fk AS
 SELECT link_binding_id, revision, fk_owner, fk_source_column,
        referenced_identity_column, content_digest
 FROM runtime_link_binding_revision;
CREATE TEMP TABLE review_baseline_operation AS
 SELECT package_id,link_binding_id,idempotency_key,operation_type,
        request_digest,committed_revision,created_at
 FROM runtime_link_binding_operation;

-- Run the unchanged review candidate in PG17 (NEVER on production data).
\i doc/design/candidates/m8-2/V11__bridge_link_binding_candidate.sql

DO $$
BEGIN
  IF EXISTS(
    SELECT 1 FROM review_baseline_fk old
    LEFT JOIN runtime_link_binding_revision now USING(link_binding_id,revision)
    WHERE now.link_binding_id IS NULL
       OR old.fk_owner IS DISTINCT FROM now.fk_owner
       OR old.fk_source_column IS DISTINCT FROM now.fk_source_column
       OR old.referenced_identity_column IS DISTINCT FROM now.referenced_identity_column
       OR old.content_digest IS DISTINCT FROM now.content_digest
       OR now.mapping_mode <> 'FOREIGN_KEY'
  ) THEN RAISE EXCEPTION 'V10 FK history or digest was rewritten'; END IF;

  IF EXISTS(
    SELECT 1 FROM review_baseline_operation old
    LEFT JOIN runtime_link_binding_operation now
      ON now.package_id=old.package_id AND now.idempotency_key=old.idempotency_key
    WHERE now.package_id IS NULL
       OR old.link_binding_id IS DISTINCT FROM now.link_binding_id
       OR old.operation_type IS DISTINCT FROM now.operation_type
       OR old.request_digest IS DISTINCT FROM now.request_digest
       OR old.committed_revision IS DISTINCT FROM now.committed_revision
       OR old.created_at IS DISTINCT FROM now.created_at
  ) THEN RAISE EXCEPTION 'V10 operation receipt mutated'; END IF;
END $$;

-- New Bridge immutable revision legitimately has NULL in all three FK-only
-- fields and non-NULL Bridge columns; source/target Object Bindings remain two.
BEGIN;
INSERT INTO runtime_link_binding(link_binding_id,package_id,snapshot_id,link_type_id,current_revision)
 VALUES ('30000000-0000-0000-0000-000000000002','demo-package',
         '10000000-0000-0000-0000-000000000001','order-equipment',1);
INSERT INTO runtime_link_binding_revision (
    link_binding_id,revision,snapshot_id,model_payload_sha256,link_type_hash,
    source_binding_id,source_binding_revision,source_binding_digest,
    target_binding_id,target_binding_revision,target_binding_digest,
    fk_owner,fk_source_column,referenced_identity_column,
    source_schema_fingerprint,target_schema_fingerprint,validation_scope,
    validation_quality,validation_status,checked_at,content_digest,
    mapping_mode,bridge_source_id,bridge_schema,bridge_table,
    bridge_source_fk_column,bridge_target_fk_column,bridge_schema_fingerprint
) VALUES (
    '30000000-0000-0000-0000-000000000002',1,
    '10000000-0000-0000-0000-000000000001',repeat('a',64),repeat('b',64),
    '20000000-0000-0000-0000-000000000001',2,repeat('c',64),
    '20000000-0000-0000-0000-000000000002',3,repeat('d',64),
    NULL,NULL,NULL,repeat('e',64),repeat('f',64),
    '{"coverage":"FULL"}'::jsonb,'{"matchedPairs":3}'::jsonb,'VALID',now(),
    repeat('5',64),'BRIDGE_TABLE','source-1','public',
    'maintenance_order_equipment','work_order_no','equipment_code',repeat('6',64)
);
COMMIT;

-- Never remove a negative assertion when a candidate fails. The nested PL/pgSQL
-- exception block rolls back the attempted INSERT, leaving revision #2 free
-- for each next invalid test.
CREATE OR REPLACE PROCEDURE review_expect_check_violation(label TEXT, patch JSONB)
LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    INSERT INTO runtime_link_binding_revision (
        link_binding_id,revision,snapshot_id,model_payload_sha256,link_type_hash,
        source_binding_id,source_binding_revision,source_binding_digest,
        target_binding_id,target_binding_revision,target_binding_digest,
        fk_owner,fk_source_column,referenced_identity_column,
        source_schema_fingerprint,target_schema_fingerprint,validation_scope,
        validation_quality,validation_status,checked_at,content_digest,
        mapping_mode,bridge_source_id,bridge_schema,bridge_table,
        bridge_source_fk_column,bridge_target_fk_column,bridge_schema_fingerprint
    )
    SELECT link_binding_id,2,snapshot_id,model_payload_sha256,link_type_hash,
        source_binding_id,source_binding_revision,source_binding_digest,
        target_binding_id,target_binding_revision,target_binding_digest,
        CASE WHEN patch ? 'fk_owner' THEN patch->>'fk_owner' ELSE fk_owner END,
        fk_source_column,referenced_identity_column,
        source_schema_fingerprint,target_schema_fingerprint,validation_scope,
        validation_quality,validation_status,checked_at,content_digest,
        CASE WHEN patch ? 'mapping_mode' THEN patch->>'mapping_mode' ELSE mapping_mode END,
        CASE WHEN patch ? 'bridge_source_id' THEN patch->>'bridge_source_id' ELSE bridge_source_id END,
        CASE WHEN patch ? 'bridge_schema' THEN patch->>'bridge_schema' ELSE bridge_schema END,
        CASE WHEN patch ? 'bridge_table' THEN patch->>'bridge_table' ELSE bridge_table END,
        CASE WHEN patch ? 'bridge_source_fk_column'
          THEN patch->>'bridge_source_fk_column' ELSE bridge_source_fk_column END,
        CASE WHEN patch ? 'bridge_target_fk_column'
          THEN patch->>'bridge_target_fk_column' ELSE bridge_target_fk_column END,
        CASE WHEN patch ? 'bridge_schema_fingerprint'
          THEN patch->>'bridge_schema_fingerprint' ELSE bridge_schema_fingerprint END
    FROM runtime_link_binding_revision
    WHERE link_binding_id='30000000-0000-0000-0000-000000000002' AND revision=1;

    RAISE EXCEPTION 'INVALID ACCEPTANCE [%]: Bridge CHECK permitted invalid row',label;
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'PASS rejected invalid candidate: %',label;
  END;
END $$;

CALL review_expect_check_violation('NULL bridge schema (PG CHECK UNKNOWN)',
  '{"bridge_schema":null}');
CALL review_expect_check_violation('wrong bridge schema', '{"bridge_schema":"private"}');
CALL review_expect_check_violation('NULL bridge fingerprint',
  '{"bridge_schema_fingerprint":null}');
CALL review_expect_check_violation('bad bridge fingerprint',
  '{"bridge_schema_fingerprint":"not-a-digest"}');
CALL review_expect_check_violation('Bridge FK placeholder forbidden',
  '{"fk_owner":"SOURCE"}');
CALL review_expect_check_violation('FK mode with non-NULL Bridge columns forbidden',
  '{"mapping_mode":"FOREIGN_KEY"}');
CALL review_expect_check_violation('unknown mode', '{"mapping_mode":"OTHER"}');
CALL review_expect_check_violation('blank bridge sourceId', '{"bridge_source_id":""}');
CALL review_expect_check_violation('wrong table identifier',
  '{"bridge_table":"maintenance-order-equipment"}');
CALL review_expect_check_violation('identical endpoint FK columns',
  '{"bridge_target_fk_column":"work_order_no"}');
CALL review_expect_check_violation('NULL source FK column',
  '{"bridge_source_fk_column":null}');

-- Existing V10 triggers still forbid historical mutation, including the newly
-- added mode discriminator. A failed update does not corrupt the transaction.
DO $$
BEGIN
  BEGIN
    UPDATE runtime_link_binding_revision
      SET mapping_mode='BRIDGE_TABLE'
      WHERE link_binding_id='30000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'FAILED: historical FK revision was mutable';
  EXCEPTION WHEN OTHERS THEN
    IF SQLERRM <> 'runtime_link_binding_revision rows are immutable'
      THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE runtime_link_binding_operation
      SET request_digest=repeat('f',64)
      WHERE idempotency_key='40000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'FAILED: historical operation receipt was mutable';
  EXCEPTION WHEN OTHERS THEN
    IF SQLERRM <> 'runtime_link_binding_operation rows are immutable'
      THEN RAISE; END IF;
  END;
  IF EXISTS (
    SELECT 1 FROM runtime_link_binding_revision
    WHERE link_binding_id='30000000-0000-0000-0000-000000000002'
      AND revision=2
  ) THEN RAISE EXCEPTION 'negative cases left an invalid revision'; END IF;
END $$;

\echo 'PASS: V10 original FK + receipt unchanged; Bridge mode valid; 11 invalid cases rejected; immutable triggers enforced'
