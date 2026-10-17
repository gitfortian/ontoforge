-- Local-only MVP sandbox: explicitly synthetic manufacturing facts, never MES/ERP.
-- Separate from Native SchemaSnapshot/Release and from any user's industrial tables.
CREATE TABLE mvp_source_orders (
  order_id TEXT PRIMARY KEY,
  customer_name TEXT NOT NULL
);
CREATE TABLE mvp_source_work_orders (
  work_order_id TEXT PRIMARY KEY,
  order_id TEXT NOT NULL REFERENCES mvp_source_orders(order_id)
);
CREATE TABLE mvp_source_batches (
  batch_id TEXT PRIMARY KEY,
  quality_status TEXT NOT NULL,
  work_order_id TEXT NOT NULL REFERENCES mvp_source_work_orders(work_order_id)
);
INSERT INTO mvp_source_orders(order_id,customer_name) VALUES
 ('ORD-9001','华南装备'),('ORD-9002','江苏制造');
INSERT INTO mvp_source_work_orders(work_order_id,order_id) VALUES
 ('WO-1001','ORD-9001'),('WO-1002','ORD-9002');
INSERT INTO mvp_source_batches(batch_id,quality_status,work_order_id) VALUES
 ('B2026-001','BLOCKED','WO-1001'),
 ('B2026-002','PASSED','WO-1002');
-- Demo binding is an explicit durable source-map at the exact draft revision;
-- no schema publication, activation or production connector is implied.
CREATE TABLE mvp_demo_binding (
  package_id TEXT PRIMARY KEY,
  draft_id UUID NOT NULL REFERENCES native_package_draft(draft_id) ON DELETE RESTRICT,
  bound_revision BIGINT NOT NULL CHECK(bound_revision > 0),
  source_map JSONB NOT NULL CHECK(jsonb_typeof(source_map)='object'),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
