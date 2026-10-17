-- M2 local-only multi-path relationship-fact fixture.
-- Intentionally separate from V4, V5 and existing source rows: never rewrite a Flyway migration.
-- A repeated business ID represents MULTIPLE relationship facts, not multiple entity identities.
CREATE TABLE demo_impact_lots (lot_no TEXT NOT NULL, outcome TEXT NOT NULL, job_ref TEXT NOT NULL);
CREATE TABLE demo_impact_jobs (job_no TEXT NOT NULL, sales_ref TEXT NOT NULL);
CREATE TABLE demo_impact_orders (sales_no TEXT PRIMARY KEY, buyer TEXT NOT NULL);

INSERT INTO demo_impact_lots VALUES
('LOT-900','BLOCKED','JOB-70'),('LOT-900','BLOCKED','JOB-71'),
('LOT-900','BLOCKED','JOB-70'),
('LOT-901','PASSED','JOB-MISSING'),
('LOT-902','BLOCKED','JOB-70');
INSERT INTO demo_impact_jobs VALUES
('JOB-70','SO-91'),('JOB-70','SO-92'),
('JOB-70','SO-91'),('JOB-71','SO-92'),('JOB-71','SO-93'),
('JOB-72','SO-MISSING');
INSERT INTO demo_impact_orders VALUES
('SO-91','华南装备'),('SO-92','华东制造'),('SO-93','苏州制造');
CREATE INDEX demo_impact_lot_lookup ON demo_impact_lots(lot_no);
CREATE INDEX demo_impact_lot_job ON demo_impact_lots(job_ref);
CREATE INDEX demo_impact_job_no ON demo_impact_jobs(job_no);
CREATE INDEX demo_impact_job_sale ON demo_impact_jobs(sales_ref);
