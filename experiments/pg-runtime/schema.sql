-- Disposable PostgreSQL 17 experiment. Executes ONLY in the dedicated ontopoc database.
-- The role/session GUC policies are a study tool, NOT a production authentication boundary.
DROP SCHEMA IF EXISTS poc CASCADE;
CREATE SCHEMA poc;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'poc_object_reader') THEN
    CREATE ROLE poc_object_reader NOLOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'poc_finance_reader') THEN
    CREATE ROLE poc_finance_reader NOLOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
END $$;

GRANT USAGE ON SCHEMA poc TO poc_object_reader, poc_finance_reader;

CREATE TABLE poc.material_lot (
  id bigint PRIMARY KEY, workspace_id text NOT NULL, visibility text NOT NULL
);
CREATE TABLE poc.product_lot (
  id bigint PRIMARY KEY, material_id bigint NOT NULL REFERENCES poc.material_lot(id),
  workspace_id text NOT NULL, visibility text NOT NULL
);
CREATE TABLE poc.work_order (
  id bigint PRIMARY KEY, workspace_id text NOT NULL, visibility text NOT NULL
);
CREATE TABLE poc.customer_order (
  id bigint PRIMARY KEY, amount_cents bigint NOT NULL,
  workspace_id text NOT NULL, visibility text NOT NULL
);
CREATE TABLE poc.quality_event (
  id bigint PRIMARY KEY, material_id bigint NOT NULL REFERENCES poc.material_lot(id),
  status text NOT NULL, workspace_id text NOT NULL, visibility text NOT NULL
);
CREATE TABLE poc.lot_workorder (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  lot_id bigint NOT NULL REFERENCES poc.product_lot(id),
  workorder_id bigint NOT NULL REFERENCES poc.work_order(id),
  workspace_id text NOT NULL, visibility text NOT NULL
);
CREATE TABLE poc.workorder_order (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workorder_id bigint NOT NULL REFERENCES poc.work_order(id),
  order_id bigint NOT NULL REFERENCES poc.customer_order(id),
  workspace_id text NOT NULL, visibility text NOT NULL
);

CREATE INDEX ix_product_material ON poc.product_lot(material_id,workspace_id,id);
CREATE INDEX ix_quality_status_material ON poc.quality_event(status,material_id,workspace_id);
CREATE INDEX ix_lw_lot ON poc.lot_workorder(lot_id,workorder_id);
CREATE INDEX ix_wo_order ON poc.workorder_order(workorder_id,order_id);
CREATE INDEX ix_order_workspace ON poc.customer_order(workspace_id,id);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'material_lot','product_lot','work_order','customer_order',
    'quality_event','lot_workorder','workorder_order'
  ]
  LOOP
    EXECUTE format('ALTER TABLE poc.%I ENABLE ROW LEVEL SECURITY',t);
    EXECUTE format('ALTER TABLE poc.%I FORCE ROW LEVEL SECURITY',t);
    EXECUTE format(
      'CREATE POLICY tenant_clearance ON poc.%I FOR SELECT TO poc_object_reader, poc_finance_reader '
      || 'USING (workspace_id=current_setting(''poc.workspace'',true) '
      || 'AND (visibility=''PUBLIC'' OR current_setting(''poc.clearance'',true)=''RESTRICTED''))',
      t
    );
    IF t <> 'customer_order' THEN
      EXECUTE format('GRANT SELECT ON poc.%I TO poc_object_reader,poc_finance_reader',t);
    END IF;
  END LOOP;
END $$;

GRANT SELECT (id,workspace_id,visibility) ON poc.customer_order TO poc_object_reader,poc_finance_reader;
GRANT SELECT (amount_cents) ON poc.customer_order TO poc_finance_reader;

-- Operational command tables deliberately separate from business objects.
CREATE TABLE poc.source_fact (
  lot_id text PRIMARY KEY, workspace_id text NOT NULL,
  status text NOT NULL, revision bigint NOT NULL
);
CREATE TABLE poc.active_contract (
  id boolean PRIMARY KEY DEFAULT true CHECK (id),
  schema_release text NOT NULL, binding_version text NOT NULL,
  function_digest text NOT NULL, policy_version text NOT NULL
);
INSERT INTO poc.active_contract(id,schema_release,binding_version,function_digest,policy_version)
VALUES (true,'s1','b1','f1','p1');

CREATE TABLE poc.action_attempt (
  id uuid PRIMARY KEY, workspace_id text NOT NULL,
  idem_key text NOT NULL, payload jsonb NOT NULL, payload_hash text NOT NULL,
  lot_id text NOT NULL, requester text NOT NULL, approver text,
  schema_release text NOT NULL, binding_version text NOT NULL,
  function_digest text NOT NULL, policy_version text NOT NULL,
  source_revision bigint NOT NULL,
  state text NOT NULL CHECK(state IN
    ('PROPOSED','APPROVED','QUEUED','DISPATCHING','UNKNOWN',
     'VERIFIED','FAILED','BLOCKED_NEEDS_REVIEW')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (workspace_id,idem_key)
);
CREATE TABLE poc.action_outbox (
  attempt_id uuid PRIMARY KEY REFERENCES poc.action_attempt(id),
  state text NOT NULL CHECK(state IN('QUEUED','LEASED','UNKNOWN','DONE','BLOCKED')),
  lease_owner text, lease_until timestamptz, claim_token uuid,
  claim_count integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_outbox_ready ON poc.action_outbox(state,lease_until,created_at)
 WHERE state IN ('QUEUED','LEASED');
CREATE TABLE poc.edit_overlay (
  attempt_id uuid PRIMARY KEY REFERENCES poc.action_attempt(id),
  lot_id text NOT NULL, status text NOT NULL, source_revision bigint NOT NULL,
  verified_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE poc.action_event (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  attempt_id uuid NOT NULL REFERENCES poc.action_attempt(id),
  event_type text NOT NULL, detail jsonb NOT NULL DEFAULT '{}',
  at timestamptz NOT NULL DEFAULT clock_timestamp()
);
