"""PostgreSQL 17 experimental gates for native ObjectSet and transactional Action Outbox.

Requires PostgreSQL, psycopg 3, a DISPOSABLE ontopoc database, and Python 3.11+.
Never run against an ERP, business or shared production database.
"""
from __future__ import annotations

import concurrent.futures
import hashlib
import json
import os
import socket
import sqlite3
import statistics
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
from contextlib import contextmanager
from pathlib import Path

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

ROOT = Path(__file__).resolve().parent
DSN = os.environ.get("POC_PG_DSN", "postgresql://postgres:postgres@127.0.0.1:5432/ontopoc")
LOTS = int(os.environ.get("POC_LOTS", "20000"))
if LOTS < 1000 or LOTS > 500000:
    raise RuntimeError("POC_LOTS must be between 1,000 and 500,000")

PATH = """
FROM poc.quality_event q
JOIN poc.material_lot m ON m.id=q.material_id
JOIN poc.product_lot p ON p.material_id=m.id
JOIN poc.lot_workorder lw ON lw.lot_id=p.id
JOIN poc.work_order w ON w.id=lw.workorder_id
JOIN poc.workorder_order wo ON wo.workorder_id=w.id
JOIN poc.customer_order o ON o.id=wo.order_id
WHERE q.status=%s
"""
PAGE_SQL = "WITH reached AS (SELECT DISTINCT o.id " + PATH + ") SELECT id FROM reached WHERE id>%s ORDER BY id LIMIT %s"
AGG_SQL = ("WITH reached AS (SELECT DISTINCT o.id " + PATH + ") "
           "SELECT count(*) AS total,coalesce(sum(o.amount_cents),0) AS amount "
           "FROM reached r JOIN poc.customer_order o ON o.id=r.id")
COUNT_SQL = "WITH reached AS (SELECT DISTINCT o.id " + PATH + ") SELECT count(*) FROM reached"
RAW_SQL = "SELECT count(*) " + PATH

def conn():
    return psycopg.connect(DSN, row_factory=dict_row)

def setup():
    with conn() as db:
        db.execute((ROOT / "schema.sql").read_text(encoding="utf-8"))
    seed(LOTS)

def seed(lots):
    with conn() as db:
        db.execute("TRUNCATE poc.workorder_order,poc.lot_workorder,poc.quality_event,"
                   "poc.product_lot,poc.material_lot,poc.work_order,poc.customer_order RESTART IDENTITY CASCADE")
        db.execute("INSERT INTO poc.material_lot SELECT i,"
                   "CASE WHEN i%%10=0 THEN 'B' ELSE 'A' END,'PUBLIC'"
                   " FROM generate_series(1,%s) AS s(i)", (lots // 10,))
        db.execute("INSERT INTO poc.work_order SELECT i,"
                   "CASE WHEN i%%10=0 THEN 'B' ELSE 'A' END,'PUBLIC'"
                   " FROM generate_series(1,%s) AS s(i)", (lots // 2,))
        db.execute("INSERT INTO poc.customer_order SELECT i,1000+(i%%100)*25,"
                   "CASE WHEN ((i+1)/2)%%10=0 THEN 'B' ELSE 'A' END,"
                   "CASE WHEN i%%17=0 THEN 'RESTRICTED' ELSE 'PUBLIC' END"
                   " FROM generate_series(1,%s) AS s(i)", (lots,))
        db.execute("INSERT INTO poc.product_lot SELECT i,((i-1)/10)+1,"
                   "CASE WHEN (((i-1)/10)+1)%%10=0 THEN 'B' ELSE 'A' END,"
                   "CASE WHEN i%%19=0 THEN 'RESTRICTED' ELSE 'PUBLIC' END"
                   " FROM generate_series(1,%s) AS s(i)", (lots,))
        db.execute("INSERT INTO poc.quality_event SELECT i,i,"
                   "CASE WHEN i%%4=0 THEN 'HELD' ELSE 'PASSED' END,"
                   "CASE WHEN i%%10=0 THEN 'B' ELSE 'A' END,'PUBLIC'"
                   " FROM generate_series(1,%s) AS s(i)", (lots // 10,))
        db.execute("INSERT INTO poc.lot_workorder(lot_id,workorder_id,workspace_id,visibility)"
                   " SELECT i,((i-1)/2)+1,"
                   "CASE WHEN (((i-1)/10)+1)%%10=0 THEN 'B' ELSE 'A' END,'PUBLIC'"
                   " FROM generate_series(1,%s) AS s(i)", (lots,))
        db.execute("INSERT INTO poc.workorder_order(workorder_id,order_id,workspace_id,visibility)"
                   " SELECT ((i-1)/2)+1,i,"
                   "CASE WHEN (((i-1)/2)+1)%%10=0 THEN 'B' ELSE 'A' END,'PUBLIC'"
                   " FROM generate_series(1,%s) AS s(i)", (lots,))
        db.execute("INSERT INTO poc.workorder_order(workorder_id,order_id,workspace_id,visibility)"
                   " SELECT ((i-1)/2)+1,i,"
                   "CASE WHEN (((i-1)/2)+1)%%10=0 THEN 'B' ELSE 'A' END,'PUBLIC'"
                   " FROM generate_series(1,%s) AS s(i) WHERE i%%13=0", (lots,))
    with conn() as db:
        for table in ("material_lot","product_lot","work_order","customer_order",
                      "quality_event","lot_workorder","workorder_order"):
            db.execute("ANALYZE poc." + table)

@contextmanager
def reader(workspace="A", clearance="PUBLIC", finance=False):
    with conn() as db:
        db.execute("SET LOCAL ROLE " + ("poc_finance_reader" if finance else "poc_object_reader"))
        db.execute("SELECT set_config('poc.workspace',%s,true)", (workspace,))
        db.execute("SELECT set_config('poc.clearance',%s,true)", (clearance,))
        yield db

def validate_status(value):
    if value not in ("HELD","PASSED"):
        raise ValueError("UNKNOWN_STATUS")

def page(status="HELD", after=0, count=25, workspace="A", clearance="PUBLIC"):
    validate_status(status)
    if not isinstance(after, int) or after < 0 or count < 1 or count > 100:
        raise ValueError("INVALID_PAGE")
    with reader(workspace, clearance) as db:
        return [r["id"] for r in db.execute(PAGE_SQL, (status, after, count)).fetchall()]

def cursor_signature(*, workspace, clearance, generation, schema, status, last):
    raw = json.dumps([workspace,clearance,generation,schema,status,last],separators=(",",":"))
    return hashlib.sha256(raw.encode()).hexdigest()

def request_page(cursor, *, workspace, clearance, generation, schema, status, last):
    expected = cursor_signature(workspace=workspace,clearance=clearance,
                                generation=generation,schema=schema,status=status,last=last)
    if cursor != expected:
        raise ValueError("STALE_OR_FOREIGN_CURSOR")
    return page(status, last, workspace=workspace, clearance=clearance)

def create_attempt(lot="LOT-001", idem=None, requester="operator", workspace="A"):
    idem = idem or uuid.uuid4().hex
    payload = {"operation":"HOLD","lot":lot}
    canonical = json.dumps(payload,sort_keys=True,separators=(",",":"))
    digest = hashlib.sha256(canonical.encode()).hexdigest()
    with conn() as db:
        existing = db.execute(
            "SELECT id,payload_hash,requester FROM poc.action_attempt "
            "WHERE workspace_id=%s AND idem_key=%s FOR UPDATE",(workspace,idem)).fetchone()
        if existing:
            if existing["payload_hash"] != digest or existing["requester"] != requester:
                raise ValueError("IDEMPOTENCY_COLLISION")
            return existing["id"]
        fact = db.execute("SELECT revision FROM poc.source_fact "
                          "WHERE lot_id=%s AND workspace_id=%s FOR UPDATE",
                          (lot,workspace)).fetchone()
        if not fact:
            raise ValueError("MISSING_SOURCE_FACT")
        active = db.execute("SELECT * FROM poc.active_contract WHERE id=true").fetchone()
        aid = uuid.uuid4()
        inserted = db.execute(
            "INSERT INTO poc.action_attempt"
            "(id,workspace_id,idem_key,payload,payload_hash,lot_id,requester,"
            "schema_release,binding_version,function_digest,policy_version,source_revision,state)"
            " VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'PROPOSED')"
            " ON CONFLICT(workspace_id,idem_key) DO NOTHING RETURNING id",
            (aid,workspace,idem,Jsonb(payload),digest,lot,requester,
             active["schema_release"],active["binding_version"],active["function_digest"],
             active["policy_version"],fact["revision"])).fetchone()
        if not inserted:
            winner = db.execute("SELECT id,payload_hash,requester FROM poc.action_attempt "
                                "WHERE workspace_id=%s AND idem_key=%s",(workspace,idem)).fetchone()
            if winner is None or winner["payload_hash"]!=digest or winner["requester"]!=requester:
                raise ValueError("IDEMPOTENCY_COLLISION")
            return winner["id"]
        db.execute("INSERT INTO poc.action_event(attempt_id,event_type) VALUES(%s,'PROPOSED')",(aid,))
        return aid

def approve(aid,approver="reviewer"):
    with conn() as db:
        row = db.execute("SELECT * FROM poc.action_attempt WHERE id=%s FOR UPDATE",(aid,)).fetchone()
        if not row or row["state"]!="PROPOSED":
            raise ValueError("BAD_APPROVAL_STATE")
        if row["requester"]==approver:
            raise ValueError("SELF_APPROVAL")
        db.execute("UPDATE poc.action_attempt SET state='APPROVED',approver=%s WHERE id=%s",
                   (approver,aid))

def check_contract(db,row):
    active = db.execute("SELECT * FROM poc.active_contract WHERE id=true").fetchone()
    if (row["schema_release"],row["binding_version"],row["function_digest"],row["policy_version"]) != (
       active["schema_release"],active["binding_version"],active["function_digest"],active["policy_version"]):
        raise ValueError("CONTRACT_DRIFT")
    fact = db.execute("SELECT revision FROM poc.source_fact "
                      "WHERE lot_id=%s AND workspace_id=%s",
                      (row["lot_id"],row["workspace_id"])).fetchone()
    if fact is None or fact["revision"]!=row["source_revision"]:
        raise ValueError("SOURCE_DRIFT")

def queue(aid):
    with conn() as db:
        a = db.execute("SELECT * FROM poc.action_attempt WHERE id=%s FOR UPDATE",(aid,)).fetchone()
        if a is None or a["state"]!="APPROVED":
            raise ValueError("NOT_APPROVED")
        check_contract(db,a)
        # Serialize admission for one operational target, not merely one idem key.
        # This is POC-level coarse locking; production scope must be policy-defined.
        db.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))",
                   (a["workspace_id"]+":"+a["lot_id"],))
        competing=db.execute(
            "SELECT id FROM poc.action_attempt WHERE workspace_id=%s AND lot_id=%s "
            "AND id<>%s AND state IN ('QUEUED','DISPATCHING','UNKNOWN') LIMIT 1",
            (a["workspace_id"],a["lot_id"],aid)).fetchone()
        if competing:
            raise ValueError("TARGET_OPERATION_IN_PROGRESS")
        db.execute("UPDATE poc.action_attempt SET state='QUEUED' WHERE id=%s",(aid,))
        db.execute("INSERT INTO poc.action_outbox(attempt_id,state) VALUES(%s,'QUEUED')",(aid,))
        db.execute("INSERT INTO poc.action_event(attempt_id,event_type) VALUES(%s,'QUEUED')",(aid,))

def claim(owner,lease_sec=20):
    token = uuid.uuid4()
    with conn() as db:
        claimrow = db.execute(
            "WITH next_job AS ("
            " SELECT attempt_id FROM poc.action_outbox"
            " WHERE state='QUEUED' OR (state='LEASED' AND lease_until<clock_timestamp())"
            " ORDER BY created_at,attempt_id FOR UPDATE SKIP LOCKED LIMIT 1"
            ") UPDATE poc.action_outbox o"
            " SET state='LEASED',lease_owner=%s,lease_until=clock_timestamp()+(%s * interval '1 second'),"
            " claim_token=%s,claim_count=claim_count+1"
            " FROM next_job WHERE o.attempt_id=next_job.attempt_id"
            " RETURNING o.attempt_id",
            (owner,lease_sec,token)).fetchone()
        if claimrow is None:
            return None
        aid=claimrow["attempt_id"]
        db.execute("UPDATE poc.action_attempt SET state='DISPATCHING' WHERE id=%s",(aid,))
        db.execute("INSERT INTO poc.action_event(attempt_id,event_type) VALUES(%s,'CLAIMED')",(aid,))
        return aid,token

def get_attempt(aid):
    with conn() as db:
        return db.execute("SELECT * FROM poc.action_attempt WHERE id=%s",(aid,)).fetchone()

def http_json(method,url,payload=None):
    body = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(url, method=method,data=body,
                                     headers={"Content-Type":"application/json"})
    try:
        with urllib.request.urlopen(request,timeout=2.5) as response:
            return response.status,json.load(response)
    except urllib.error.HTTPError as exc:
        try:
            return exc.code,json.loads(exc.read().decode())
        except (ValueError,UnicodeError):
            return exc.code,{}

def mark_state(aid,state):
    # Late errors may not erase an already VERIFIED receipt.
    with conn() as db:
        changed=db.execute("UPDATE poc.action_attempt SET state=%s "
                           "WHERE id=%s AND state IN ('DISPATCHING','UNKNOWN') "
                           "RETURNING id",(state,aid)).fetchone()
        if changed:
            db.execute("UPDATE poc.action_outbox SET state=%s WHERE attempt_id=%s",
                       ("UNKNOWN" if state=="UNKNOWN" else "BLOCKED",aid))
            db.execute("INSERT INTO poc.action_event(attempt_id,event_type) VALUES(%s,%s)",(aid,state))

def finalize_receipt(aid,receipt):
    a = get_attempt(aid)
    if receipt.get("state")!="CONFIRMED" or receipt.get("payload_hash")!=a["payload_hash"]:
        raise ValueError("UNVERIFIED_RECEIPT")
    with conn() as db:
        row = db.execute("SELECT * FROM poc.action_attempt WHERE id=%s FOR UPDATE",(aid,)).fetchone()
        if row["state"]=="VERIFIED":
            return "VERIFIED"
        if row["state"] not in ("DISPATCHING","UNKNOWN"):
            raise ValueError("LATE_INVALID_RECEIPT")
        db.execute("UPDATE poc.action_attempt SET state='VERIFIED' WHERE id=%s",(aid,))
        db.execute("UPDATE poc.action_outbox SET state='DONE' WHERE attempt_id=%s",(aid,))
        db.execute("INSERT INTO poc.edit_overlay(attempt_id,lot_id,status,source_revision)"
                   "VALUES(%s,%s,'HOLD',%s) ON CONFLICT(attempt_id) DO NOTHING",
                   (aid,row["lot_id"],row["source_revision"]))
        db.execute("INSERT INTO poc.action_event(attempt_id,event_type,detail)"
                   "VALUES(%s,'VERIFIED',%s)",(aid,Jsonb(receipt)))
        return "VERIFIED"

def reconcile(aid,base):
    a=get_attempt(aid)
    try:
        code,receipt=http_json("GET",base+"/receipts/"+urllib.parse.quote(a["idem_key"],safe=""))
        if code==200:
            return finalize_receipt(aid,receipt)
        if code==404:
            return None
        mark_state(aid,"UNKNOWN")
        return "UNKNOWN"
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        mark_state(aid,"UNKNOWN")
        return "UNKNOWN"

def dispatch(aid,token,base):
    with conn() as db:
        o = db.execute("SELECT state,claim_token,lease_until,"
                       "(lease_until>clock_timestamp()) AS lease_valid "
                       "FROM poc.action_outbox WHERE attempt_id=%s",(aid,)).fetchone()
        if not o or o["state"]!="LEASED" or o["claim_token"]!=token or not o["lease_valid"]:
            raise ValueError("STALE_CLAIM_TOKEN")
        a = db.execute("SELECT * FROM poc.action_attempt WHERE id=%s",(aid,)).fetchone()
        try:
            check_contract(db,a)
        except ValueError:
            mark_state(aid,"BLOCKED_NEEDS_REVIEW")
            return "BLOCKED_NEEDS_REVIEW"
    receipt=reconcile(aid,base)
    if receipt in ("VERIFIED","UNKNOWN"):
        return receipt
    a=get_attempt(aid)
    # Resend is permitted ONLY because this research ERP guarantees durable identical-key deduplication.
    try:
        code,response=http_json("POST",base+"/hold",
           {"key":a["idem_key"],"lot":a["lot_id"],"payload_hash":a["payload_hash"]})
        if code==200:
            return finalize_receipt(aid,response)
        if code==409:
            mark_state(aid,"BLOCKED_NEEDS_REVIEW")
            return "BLOCKED_NEEDS_REVIEW"
        mark_state(aid,"UNKNOWN")
        return "UNKNOWN"
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        mark_state(aid,"UNKNOWN")
        return "UNKNOWN"

def effective_object(lot):
    with conn() as db:
        s = db.execute("SELECT status,revision FROM poc.source_fact WHERE lot_id=%s",(lot,)).fetchone()
        e = db.execute(
            "SELECT source_revision FROM poc.edit_overlay "
            "WHERE lot_id=%s ORDER BY verified_at DESC LIMIT 1",(lot,)).fetchone()
    if s is None:return "NOT_FOUND"
    if e is None:return s["status"]
    if s["status"]=="HOLD":return "HOLD:SOURCE_CAUGHT_UP"
    if s["revision"]>e["source_revision"]:return "UNKNOWN:SOURCE_CONFLICT"
    return "HOLD:VERIFIED_OVERLAY"

class QueryTests(unittest.TestCase):
    def test_scope_and_clearance(self):
        with reader("A","PUBLIC") as db:
            public=db.execute("SELECT count(*) n FROM poc.customer_order").fetchone()["n"]
            self.assertGreater(public,100)
            self.assertEqual(db.execute("SELECT count(*) n FROM poc.customer_order WHERE workspace_id='B'").fetchone()["n"],0)
        with reader("A","RESTRICTED") as db:
            expanded=db.execute("SELECT count(*) n FROM poc.customer_order").fetchone()["n"]
        self.assertGreater(expanded,public)
        with reader("B","PUBLIC") as db:
            self.assertLess(db.execute("SELECT count(*) n FROM poc.customer_order").fetchone()["n"],public)

    def test_lazy_page_and_cursor_scope(self):
        xs=page("HELD")
        self.assertTrue(0<len(xs)<=25)
        self.assertEqual(xs,sorted(xs))
        ys=page("HELD",after=xs[-1])
        self.assertFalse(set(xs)&set(ys))
        c=cursor_signature(workspace="A",clearance="PUBLIC",generation="g1",schema="s1",status="HELD",last=xs[-1])
        self.assertEqual(ys,request_page(c,workspace="A",clearance="PUBLIC",generation="g1",schema="s1",status="HELD",last=xs[-1]))
        with self.assertRaises(ValueError):
            request_page(c,workspace="B",clearance="PUBLIC",generation="g1",schema="s1",status="HELD",last=xs[-1])
        with self.assertRaises(ValueError):
            request_page(c,workspace="A",clearance="PUBLIC",generation="g2",schema="s1",status="HELD",last=xs[-1])
        with self.assertRaises(ValueError):
            page("HELD'); DROP TABLE poc.material_lot;--")

    def test_deduplicate_relation_paths(self):
        with reader("A",finance=True) as db:
            raw=db.execute(RAW_SQL,("HELD",)).fetchone()["count"]
            unique=db.execute(COUNT_SQL,("HELD",)).fetchone()["count"]
            agg=db.execute(AGG_SQL,("HELD",)).fetchone()
        self.assertGreater(raw,unique)
        self.assertEqual(agg["total"],unique)
        self.assertGreater(agg["amount"],0)

    def test_sensitive_amount_requires_finance_role(self):
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with reader("A") as db:
                db.execute(AGG_SQL,("HELD",)).fetchone()
        with reader("A",finance=True) as db:
            self.assertGreater(db.execute(AGG_SQL,("HELD",)).fetchone()["amount"],0)

    def test_concurrent_read(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            rows=list(pool.map(lambda _: page("HELD"),range(8)))
        self.assertTrue(all(r==rows[0] for r in rows[1:]))

class OutboxTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.erp_path = Path(cls.tmp.name)/"erp.sqlite"
        with socket.socket() as sock:
            sock.bind(("127.0.0.1",0))
            cls.port=sock.getsockname()[1]
        cls.base=f"http://127.0.0.1:{cls.port}"
        cls.proc=subprocess.Popen([sys.executable,str(ROOT/"erp_mock.py"),str(cls.erp_path),str(cls.port)],
                                  stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        for _ in range(50):
            try:
                http_json("GET",cls.base+"/health")
                break
            except (urllib.error.URLError, ConnectionError):
                time.sleep(0.1)
        else:
            raise RuntimeError("ERP mock never started")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try: cls.proc.wait(timeout=3)
        except subprocess.TimeoutExpired: cls.proc.kill()
        cls.proc.stderr.close()
        cls.tmp.cleanup()

    def setUp(self):
        with conn() as db:
            db.execute("TRUNCATE poc.action_event,poc.edit_overlay,poc.action_outbox,"
                       "poc.action_attempt,poc.source_fact RESTART IDENTITY CASCADE")
            db.execute("UPDATE poc.active_contract SET schema_release='s1',"
                       "binding_version='b1',function_digest='f1',policy_version='p1' WHERE id=true")
            db.execute("INSERT INTO poc.source_fact VALUES('LOT-001','A','READY',1)")
        with sqlite3.connect(self.erp_path) as db:
            db.execute("DELETE FROM receipt")

    def prepare(self,key=None,lot="LOT-001"):
        aid=create_attempt(lot=lot,idem=key)
        approve(aid)
        queue(aid)
        return aid

    def test_atomic_outbox_and_self_approval(self):
        aid=create_attempt()
        with self.assertRaises(ValueError):approve(aid,"operator")
        approve(aid,"reviewer")
        queue(aid)
        with conn() as db:
            self.assertEqual(db.execute("SELECT count(*) n FROM poc.action_outbox").fetchone()["n"],1)
            self.assertEqual(get_attempt(aid)["state"],"QUEUED")

    def test_concurrent_claim_skips_locked_jobs(self):
        with conn() as db:
            for i in range(4):
                db.execute("INSERT INTO poc.source_fact "
                           "(lot_id,workspace_id,status,revision) VALUES(%s,'A','READY',1)",
                           (f"LOT-CLAIM-{i:03d}",))
        aids=[self.prepare(lot=f"LOT-CLAIM-{i:03d}") for i in range(4)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            claims=list(pool.map(lambda i:claim(f"worker-{i}"),range(8)))
        got=[c[0] for c in claims if c]
        self.assertEqual(len(got),4)
        self.assertEqual(set(got),set(aids))

    def test_crash_before_http_recovers(self):
        aid=self.prepare()
        child=subprocess.run([sys.executable,str(ROOT/"run_pg_poc.py"),"crash-before"],
                             env=os.environ.copy(),timeout=15,check=False)
        self.assertEqual(child.returncode,72)
        with conn() as db:
            db.execute("UPDATE poc.action_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE attempt_id=%s",(aid,))
        slot=claim("recovered")
        self.assertEqual(slot[0],aid)
        self.assertEqual(dispatch(*slot,self.base),"VERIFIED")
        self.assertEqual(http_json("GET",self.base+"/stats")[1]["unique_writes"],1)

    def test_crash_after_remote_commit_recovers(self):
        aid=self.prepare()
        child=subprocess.run([sys.executable,str(ROOT/"run_pg_poc.py"),"crash-after",self.base],
                             env=os.environ.copy(),timeout=15,check=False)
        self.assertEqual(child.returncode,73)
        self.assertEqual(http_json("GET",self.base+"/stats")[1]["unique_writes"],1)
        with conn() as db:
            db.execute("UPDATE poc.action_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE attempt_id=%s",(aid,))
        slot=claim("recovered")
        self.assertEqual(slot[0],aid)
        self.assertEqual(dispatch(*slot,self.base),"VERIFIED")
        self.assertEqual(http_json("GET",self.base+"/stats")[1]["unique_writes"],1)
        self.assertEqual(effective_object("LOT-001"),"HOLD:VERIFIED_OVERLAY")

    def test_unknown_is_not_failure(self):
        aid=self.prepare()
        slot=claim("worker")
        self.assertEqual(dispatch(*slot,"http://127.0.0.1:1"),"UNKNOWN")
        self.assertEqual(get_attempt(aid)["state"],"UNKNOWN")
        self.assertIsNone(reconcile(aid,self.base))
        self.assertEqual(effective_object("LOT-001"),"READY")

    def test_versions_and_source_drift(self):
        aid=create_attempt()
        approve(aid)
        with conn() as db:
            db.execute("UPDATE poc.active_contract SET policy_version='p2'")
        with self.assertRaisesRegex(ValueError,"CONTRACT_DRIFT"):
            queue(aid)
        with conn() as db:
            db.execute("UPDATE poc.active_contract SET policy_version='p1'")
            db.execute("UPDATE poc.source_fact SET revision=2")
        with self.assertRaisesRegex(ValueError,"SOURCE_DRIFT"):
            queue(aid)

    def test_dispatch_rechecks_policies(self):
        aid=self.prepare()
        slot=claim("worker")
        with conn() as db:
            db.execute("UPDATE poc.active_contract SET function_digest='f2'")
        self.assertEqual(dispatch(*slot,self.base),"BLOCKED_NEEDS_REVIEW")
        self.assertEqual(http_json("GET",self.base+"/stats")[1]["unique_writes"],0)

    def test_idempotent_payload_binding_and_authority(self):
        key=uuid.uuid4().hex
        aid=create_attempt(idem=key)
        self.assertEqual(create_attempt(idem=key),aid)
        with self.assertRaisesRegex(ValueError,"IDEMPOTENCY_COLLISION"):
            create_attempt(idem=key,requester="other")
        with self.assertRaisesRegex(ValueError,"MISSING_SOURCE_FACT"):
            create_attempt(idem=uuid.uuid4().hex,workspace="B")

    def test_source_catches_up_and_conflicts(self):
        aid=self.prepare()
        slot=claim("worker")
        self.assertEqual(dispatch(*slot,self.base),"VERIFIED")
        with conn() as db:
            db.execute("UPDATE poc.source_fact SET status='HOLD',revision=2")
        self.assertEqual(effective_object("LOT-001"),"HOLD:SOURCE_CAUGHT_UP")
        with conn() as db:
            db.execute("UPDATE poc.source_fact SET status='READY',revision=3")
        self.assertEqual(effective_object("LOT-001"),"UNKNOWN:SOURCE_CONFLICT")

def benchmark():
    from datetime import datetime,timezone
    samples=[]
    for name,sql,args in (("page",PAGE_SQL,("HELD",0,25)),
                          ("aggregate",AGG_SQL,("HELD",))):
        times=[]
        with reader("A","PUBLIC",finance=(name=="aggregate")) as db:
            for k in range(9):
                plan=db.execute("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) "+sql,args).fetchone()["QUERY PLAN"][0]
                if k:times.append(float(plan["Execution Time"]))
            times.sort()
            samples.append({"query":name,"samples":len(times),
                "execution_ms_p50":statistics.median(times),
                "execution_ms_p95":statistics.quantiles(times,n=100,method="inclusive")[94],
                "plan_root":plan["Plan"]["Node Type"],
                "shared_hit_blocks":plan["Plan"].get("Shared Hit Blocks"),
                "shared_read_blocks":plan["Plan"].get("Shared Read Blocks"),
                "last_explain_analyze_plan":plan})
    result={"postgres":None,"lots":LOTS,"time":datetime.now(timezone.utc).isoformat(),
            "queries":samples,"scope":"single-runner local PostgreSQL, no production SLA"}
    with conn() as db:
        result["postgres"]=db.execute("SHOW server_version").fetchone()["server_version"]
    out=ROOT/"artifacts"
    out.mkdir(exist_ok=True)
    (out/"results.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    print("POSTGRES_REAL_RESULT="+json.dumps(result,ensure_ascii=False))

def main():
    if len(sys.argv)>1 and sys.argv[1].startswith("crash-"):
        mode=sys.argv[1]
        slot=claim("crashing-worker")
        if slot is None:
            sys.exit(70)
        if mode=="crash-before":
            os._exit(72)
        aid,_=slot
        a=get_attempt(aid)
        http_json("POST",sys.argv[2]+"/hold",
                  {"key":a["idem_key"],"lot":a["lot_id"],"payload_hash":a["payload_hash"]})
        os._exit(73)
    setup()
    suite=unittest.TestSuite()
    suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(QueryTests))
    suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(OutboxTests))
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():
        sys.exit(1)
    benchmark()

if __name__=="__main__":
    main()
