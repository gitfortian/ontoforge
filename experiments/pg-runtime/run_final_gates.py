"""Round-three architecture gates: real PG high-fanout/RLS and Action races.

The experiment runs against an isolated/disposable 'ontopoc' PostgreSQL 17 database.
Always run run_pg_poc.py first, which recreates the disposable poc schema.
Do not point at any production or shared database.
"""
from __future__ import annotations

import concurrent.futures
import json
import os
import statistics
import time
import unittest
import uuid
from datetime import datetime, timezone
from pathlib import Path

import psycopg
import run_pg_poc as core

ROOT = Path(__file__).resolve().parent
LOTS = int(os.getenv("POC_LOTS", "100000"))
FANOUT = int(os.getenv("POC_FANOUT", "8"))
if LOTS < 10000 or LOTS > 500000 or FANOUT < 1 or FANOUT > 48:
    raise RuntimeError("out-of-budget POC configuration")
if LOTS * FANOUT > 15000000:
    raise RuntimeError("out-of-budget link count")

FAN_PATH = (
    "FROM poc.quality_event q "
    "JOIN poc.product_lot p ON p.material_id=q.material_id "
    "JOIN poc.fanout_link fl ON fl.lot_id=p.id "
    "JOIN poc.customer_order o ON o.id=fl.order_id "
)
FAN_WHERE = "WHERE q.status='HELD' "
FAN_EXPLICIT = (
    "AND q.workspace_id='A' AND q.visibility='PUBLIC' "
    "AND p.workspace_id='A' AND p.visibility='PUBLIC' "
    "AND fl.workspace_id='A' AND fl.visibility='PUBLIC' "
    "AND o.workspace_id='A' AND o.visibility='PUBLIC' "
)
FAN_AGG = ("WITH matched AS (SELECT DISTINCT o.id " + FAN_PATH + FAN_WHERE
           + ") SELECT count(*) AS total,coalesce(sum(o.amount_cents),0) AS cents "
           + "FROM matched m JOIN poc.customer_order o ON o.id=m.id")
FAN_AGG_EXPLICIT = ("WITH matched AS (SELECT DISTINCT o.id " + FAN_PATH
                   + FAN_WHERE + FAN_EXPLICIT
                   + ") SELECT count(*) AS total,coalesce(sum(o.amount_cents),0) AS cents "
                   + "FROM matched m JOIN poc.customer_order o ON o.id=m.id")
FAN_PAGE = ("WITH matched AS (SELECT DISTINCT o.id " + FAN_PATH + FAN_WHERE
            + ") SELECT id FROM matched WHERE id>0 ORDER BY id LIMIT 25")

def init_fanout():
    with core.conn() as db:
        db.execute("DROP TABLE IF EXISTS poc.fanout_link")
        db.execute(
            "CREATE TABLE poc.fanout_link("
            "lot_id bigint NOT NULL REFERENCES poc.product_lot(id), "
            "order_id bigint NOT NULL REFERENCES poc.customer_order(id), "
            "workspace_id text NOT NULL,visibility text NOT NULL)")
        # Interpolate no user-provided SQL identifier. All generated rows remain isolated POC data.
        db.execute(
            "INSERT INTO poc.fanout_link(lot_id,order_id,workspace_id,visibility) "
            "SELECT p.id, (((p.id-1) + g.n*97) %% %s) + 1, p.workspace_id,"
            "CASE WHEN (p.id+g.n) %% 23=0 THEN 'RESTRICTED' ELSE 'PUBLIC' END "
            "FROM poc.product_lot p CROSS JOIN generate_series(0,%s - 1) AS g(n)",
            (LOTS,FANOUT))
        db.execute("CREATE INDEX ix_fanout_lot ON poc.fanout_link(lot_id,order_id)")
        db.execute("CREATE INDEX ix_fanout_order ON poc.fanout_link(order_id,lot_id)")
        db.execute("ANALYZE poc.fanout_link")
        db.execute("ALTER TABLE poc.fanout_link ENABLE ROW LEVEL SECURITY")
        db.execute("ALTER TABLE poc.fanout_link FORCE ROW LEVEL SECURITY")
        db.execute(
            "CREATE POLICY isolation ON poc.fanout_link FOR SELECT "
            "TO poc_object_reader,poc_finance_reader USING "
            "(workspace_id=current_setting('poc.workspace',true) "
            "AND (visibility='PUBLIC' OR current_setting('poc.clearance',true)='RESTRICTED'))")
        db.execute("GRANT SELECT ON poc.fanout_link TO poc_object_reader,poc_finance_reader")

class FanoutQueryGates(unittest.TestCase):
    def test_link_cardinality_is_real(self):
        with core.conn() as db:
            n=db.execute("SELECT count(*) AS n FROM poc.fanout_link").fetchone()["n"]
            self.assertEqual(n,LOTS*FANOUT)
            max_edges=db.execute("SELECT count(*) AS n FROM poc.fanout_link WHERE lot_id=1").fetchone()["n"]
            self.assertEqual(max_edges,FANOUT)

    def test_business_identity_dedup(self):
        with core.reader("A","PUBLIC",finance=True) as db:
            agg=db.execute(FAN_AGG).fetchone()
            raw=db.execute("SELECT count(*) AS n "+FAN_PATH+FAN_WHERE).fetchone()["n"]
        self.assertGreater(agg["total"],0)
        self.assertGreater(raw,agg["total"])
        self.assertGreater(agg["cents"],0)

    def test_rls_matches_explicit_predicates(self):
        with core.reader("A","PUBLIC",finance=True) as db:
            visible=db.execute(FAN_AGG).fetchone()
        with core.conn() as db:
            explicit=db.execute(FAN_AGG_EXPLICIT).fetchone()
        self.assertEqual(dict(visible),dict(explicit))

    def test_scope_clearance_and_restricted_field(self):
        with core.reader("A","PUBLIC",finance=True) as db:
            a=db.execute(FAN_AGG).fetchone()["total"]
        with core.reader("A","RESTRICTED",finance=True) as db:
            elevated=db.execute(FAN_AGG).fetchone()["total"]
        with core.reader("B","PUBLIC",finance=True) as db:
            b=db.execute(FAN_AGG).fetchone()["total"]
        self.assertGreater(a,0)
        self.assertGreater(elevated,a)
        self.assertGreater(b,0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with core.reader("A","PUBLIC",finance=False) as db:
                db.execute(FAN_AGG).fetchone()

    def test_page_stays_bounded_not_full_load(self):
        with core.reader("A","PUBLIC") as db:
            ids=[x["id"] for x in db.execute(FAN_PAGE).fetchall()]
        self.assertEqual(len(ids),25)
        self.assertEqual(ids,sorted(set(ids)))

    def test_concurrent_roles_do_not_cross(self):
        def count(scope):
            with core.reader(scope,"PUBLIC",finance=True) as db:
                return db.execute(FAN_AGG).fetchone()["total"]
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            vals=list(pool.map(count,["A","B","A","B","A","B"]))
        self.assertEqual(vals[0],vals[2])
        self.assertEqual(vals[1],vals[3])
        self.assertNotEqual(vals[0],vals[1])

class OutboxRaceGates(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        core.OutboxTests.setUpClass.__func__(cls)

    @classmethod
    def tearDownClass(cls):
        core.OutboxTests.tearDownClass.__func__(cls)

    def setUp(self):
        core.OutboxTests.setUp(self)

    def test_parallel_identical_idempotency_key(self):
        key=uuid.uuid4().hex
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            ids=list(pool.map(lambda _:core.create_attempt(idem=key), range(12)))
        self.assertEqual(len(set(ids)),1)
        with core.conn() as db:
            self.assertEqual(db.execute(
                "SELECT count(*) AS n FROM poc.action_attempt WHERE idem_key=%s",(key,)).fetchone()["n"],1)
            self.assertEqual(db.execute(
                "SELECT count(*) AS n FROM poc.action_event WHERE event_type='PROPOSED'").fetchone()["n"],1)

    def test_same_idem_different_requester_rejected(self):
        key=uuid.uuid4().hex
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            fut_a=pool.submit(core.create_attempt,idem=key,requester="operator")
            fut_b=pool.submit(core.create_attempt,idem=key,requester="attacker")
            results=[]
            for fut in [fut_a,fut_b]:
                try: results.append(("ok",fut.result()))
                except Exception as e: results.append(("err",str(e)))
        self.assertEqual([x[0] for x in results].count("ok"),1)
        self.assertIn("IDEMPOTENCY_COLLISION",str(results))

    def test_target_conflict_is_rejected(self):
        a=core.create_attempt();core.approve(a);core.queue(a)
        b=core.create_attempt();core.approve(b)
        with self.assertRaisesRegex(ValueError,"TARGET_OPERATION_IN_PROGRESS"):
            core.queue(b)
        with core.conn() as db:
            self.assertEqual(db.execute(
                "SELECT count(*) AS n FROM poc.action_outbox").fetchone()["n"],1)

    def test_parallel_target_queue_only_one_wins(self):
        a,b=core.create_attempt(),core.create_attempt()
        core.approve(a);core.approve(b)
        def attempt(aid):
            try:core.queue(aid);return "QUEUED"
            except ValueError as e:return str(e)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            result=list(pool.map(attempt,[a,b]))
        self.assertEqual(result.count("QUEUED"),1)
        self.assertEqual(result.count("TARGET_OPERATION_IN_PROGRESS"),1)

    def test_expired_claim_does_not_dispatch(self):
        aid=core.create_attempt();core.approve(aid);core.queue(aid)
        old=core.claim("worker-old")
        with core.conn() as db:
            db.execute("UPDATE poc.action_outbox "
                       "SET lease_until=clock_timestamp()-interval '1 second' WHERE attempt_id=%s",(aid,))
        with self.assertRaisesRegex(ValueError,"STALE_CLAIM_TOKEN"):
            core.dispatch(*old,self.base)
        self.assertEqual(core.http_json("GET",self.base+"/stats")[1]["unique_writes"],0)

    def test_stolen_claim_old_owner_cannot_send(self):
        aid=core.create_attempt();core.approve(aid);core.queue(aid)
        old=core.claim("worker-old")
        with core.conn() as db:
            db.execute("UPDATE poc.action_outbox "
                       "SET lease_until=clock_timestamp()-interval '1 second' WHERE attempt_id=%s",(aid,))
        new=core.claim("worker-new")
        self.assertEqual(old[0],new[0])
        self.assertNotEqual(old[1],new[1])
        with self.assertRaisesRegex(ValueError,"STALE_CLAIM_TOKEN"):
            core.dispatch(*old,self.base)
        self.assertEqual(core.dispatch(*new,self.base),"VERIFIED")
        self.assertEqual(core.http_json("GET",self.base+"/stats")[1]["unique_writes"],1)

    def test_stale_network_error_cannot_overwrite_new_owner(self):
        aid=core.create_attempt();core.approve(aid);core.queue(aid)
        previous=core.claim("worker-old")
        with core.conn() as db:
            db.execute("UPDATE poc.action_outbox "
                       "SET lease_until=clock_timestamp()-interval '1 second' WHERE attempt_id=%s",(aid,))
        next_owner=core.claim("worker-new")
        self.assertEqual(next_owner[0],aid)
        self.assertFalse(core.mark_state(aid,"UNKNOWN",token=previous[1]))
        self.assertFalse(core.mark_state(aid,"UNKNOWN"))
        with core.conn() as db:
            row=db.execute("SELECT state,claim_token FROM poc.action_outbox "
                           "WHERE attempt_id=%s",(aid,)).fetchone()
        self.assertEqual(row["state"],"LEASED")
        self.assertEqual(row["claim_token"],next_owner[1])
        self.assertEqual(core.dispatch(*next_owner,self.base),"VERIFIED")
        self.assertEqual(core.http_json("GET",self.base+"/stats")[1]["unique_writes"],1)

    def test_late_unknown_cannot_erase_receipt(self):
        aid=core.create_attempt();core.approve(aid);core.queue(aid)
        slot=core.claim("worker")
        self.assertEqual(core.dispatch(*slot,self.base),"VERIFIED")
        core.mark_state(aid,"UNKNOWN")
        self.assertEqual(core.get_attempt(aid)["state"],"VERIFIED")
        with core.conn() as db:
            self.assertEqual(db.execute("SELECT state FROM poc.action_outbox "
                                        "WHERE attempt_id=%s",(aid,)).fetchone()["state"],"DONE")

    def test_unknown_is_not_automatically_retried(self):
        aid=core.create_attempt();core.approve(aid);core.queue(aid)
        slot=core.claim("worker")
        self.assertEqual(core.dispatch(*slot,"http://127.0.0.1:1"),"UNKNOWN")
        self.assertIsNone(core.claim("auto-worker"))
        self.assertEqual(core.get_attempt(aid)["state"],"UNKNOWN")
        # A real ERP may return a stale 404. Without an adapter contract, do not blindly resend.
        self.assertIsNone(core.reconcile(aid,self.base))

    def test_claim_many_targets_under_contention(self):
        with core.conn() as db:
            for n in range(14):
                db.execute("INSERT INTO poc.source_fact VALUES(%s,'A','READY',1)",
                           (f"LOT-STRESS-{n:03}",))
        ids=[]
        for n in range(14):
            aid=core.create_attempt(lot=f"LOT-STRESS-{n:03}")
            core.approve(aid);core.queue(aid)
            ids.append(aid)
        with concurrent.futures.ThreadPoolExecutor(max_workers=18) as pool:
            slots=list(pool.map(lambda i:core.claim(f"w-{i}"),range(22)))
        slots=[s for s in slots if s]
        self.assertEqual(len(slots),14)
        self.assertEqual({x[0] for x in slots},set(ids))
        with concurrent.futures.ThreadPoolExecutor(max_workers=7) as pool:
            final=list(pool.map(lambda slot:core.dispatch(*slot,self.base),slots))
        self.assertEqual(final,["VERIFIED"]*14)
        self.assertEqual(core.http_json("GET",self.base+"/stats")[1]["unique_writes"],14)

def explain(db,sql):
    db.execute("SET LOCAL statement_timeout = '180s'")
    return db.execute("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) "+sql).fetchone()["QUERY PLAN"][0]

def timed_plans(role,query,repeat=5):
    times=[]
    last=None
    for n in range(repeat+1):
        if role=="rls":
            with core.reader("A","PUBLIC",finance=True) as db:
                last=explain(db,query)
        else:
            with core.conn() as db:
                last=explain(db,query)
        if n:times.append(float(last["Execution Time"]))
    return {
        "n":len(times),
        "p50_ms":statistics.median(times),
        "p95_sample_ms":statistics.quantiles(times,n=100,method="inclusive")[94],
        "max_ms":max(times),
        "last_plan":last,
        "last_root":last["Plan"]["Node Type"],
        "shared_hit_blocks":last["Plan"].get("Shared Hit Blocks"),
        "shared_read_blocks":last["Plan"].get("Shared Read Blocks"),
        "temp_written_blocks":last["Plan"].get("Temp Written Blocks")
    }

def measure():
    p={}
    for key,role,sql in (
            ("fanout_rls_aggregate","rls",FAN_AGG),
            ("fanout_explicit_predicate_aggregate","explicit",FAN_AGG_EXPLICIT),
            ("fanout_rls_first_page","rls",FAN_PAGE)):
        print("MEASURING",key,flush=True)
        p[key]=timed_plans(role,sql)
        print("MEASURED",key,round(p[key]["p50_ms"],2),flush=True)
    result={
        "stage":"third-round architecture gate / disposable PostgreSQL experiment",
        "utc":datetime.now(timezone.utc).isoformat(),
        "lots":LOTS, "links":LOTS*FANOUT, "fanout":FANOUT,
        "postgres":None,"measurements":p,
        "caveats":[
            "No realistic ERP or true end-to-end authorization service",
            "RLS vs explicit predicate are different SQL/roles; ratio is not pure RLS overhead",
            "Warm-cache CI service-side EXPLAIN times are not production SLA",
            "High fanout generates synthetic uniform links, not empirical industrial skew",
            "No guarantee of exactly-once distributed side effects",
            "No 10m object/p99 concurrency/ABAC revocation stress"
        ]}
    with core.conn() as db:
        result["postgres"]=db.execute("SHOW server_version").fetchone()["server_version"]
    out=ROOT/"artifacts"
    out.mkdir(exist_ok=True)
    (out/"final_gates.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),
                                         encoding="utf-8")
    print("FINAL_GATE_RESULT="+json.dumps({
        "lots":LOTS,"fanout":FANOUT,"links":LOTS*FANOUT,
        "metrics":{k:{j:v for j,v in d.items() if j!="last_plan"} for k,d in p.items()}
    },ensure_ascii=False),flush=True)

def main():
    print("GENERATE_FANOUT",LOTS,FANOUT,flush=True)
    init_fanout()
    suite=unittest.TestSuite()
    suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(FanoutQueryGates))
    suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(OutboxRaceGates))
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():
        raise SystemExit(1)
    measure()

if __name__=="__main__":
    main()
