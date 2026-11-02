-- M8.1 isolated CI fixture ONLY, in disposable ontoforge_test_e2e PostgreSQL 17.
-- Caller runs after provision-m5-source-reader.sql. No production DDL.
CREATE TABLE public.m8_factory_cells (
  cell_code TEXT PRIMARY KEY,
  cell_name TEXT NOT NULL,
  site TEXT NOT NULL,
  cost NUMERIC(12,2) NOT NULL
);
CREATE TABLE public.m8_quality_events (
  event_code TEXT PRIMARY KEY,
  cell_code TEXT NULL,
  status TEXT NOT NULL,
  amount NUMERIC(12,2) NULL
);
INSERT INTO public.m8_factory_cells(cell_code,cell_name,site,cost) VALUES
  ('CELL-01','CNC Cell 1','A',100.00),('CELL-02','CNC Cell 2','B',200.00);
INSERT INTO public.m8_quality_events(event_code,cell_code,status,amount) VALUES
  ('QE-11','CELL-01','PASS',10.00),('QE-12','CELL-01','PASS',20.00),
  ('QE-13',NULL,'HOLD',NULL),('QE-14','UNKNOWN-CELL','FAIL',40.00);
GRANT SELECT ON TABLE public.m8_factory_cells,public.m8_quality_events
  TO ontoforge_binding_reader;

-- M8.2: one explicit allowlisted Bridge fact table, not a third ObjectBinding.
CREATE TABLE public.m8_cell_event_bridge (
  cell_code TEXT NULL,
  event_code TEXT NULL
);
INSERT INTO public.m8_cell_event_bridge(cell_code,event_code) VALUES
  ('CELL-01','QE-11'),('CELL-01','QE-11'),
  ('CELL-01','QE-12'),('CELL-02','QE-12'),
  (NULL,'QE-13'),('CELL-01',NULL),(NULL,NULL),
  ('CELL-NO','QE-11'),('CELL-01','QE-NO'),('CELL-NO','QE-NO');
GRANT SELECT ON TABLE public.m8_cell_event_bridge TO ontoforge_binding_reader;
