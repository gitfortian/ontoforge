-- CI-only sample of user-supplied local demo_ tables with independent column names.
-- Never execute against an industrial or production database.
CREATE TABLE demo_sales_orders (
  sales_no TEXT PRIMARY KEY,
  buyer TEXT NOT NULL
);
CREATE TABLE demo_prod_jobs (
  job_no TEXT PRIMARY KEY,
  sales_ref TEXT NOT NULL REFERENCES demo_sales_orders(sales_no)
);
CREATE TABLE demo_quality_lots (
  lot_no TEXT PRIMARY KEY,
  outcome TEXT NOT NULL,
  job_ref TEXT NOT NULL REFERENCES demo_prod_jobs(job_no)
);
INSERT INTO demo_sales_orders(sales_no,buyer) VALUES ('SO-88','回归测试买方');
INSERT INTO demo_prod_jobs(job_no,sales_ref) VALUES ('JOB-77','SO-88');
INSERT INTO demo_quality_lots(lot_no,outcome,job_ref) VALUES ('LOT-701','QA_FAIL','JOB-77');
