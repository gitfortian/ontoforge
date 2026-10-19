"""Disposable PostgreSQL 17 research only: M8.1 FK/identity and CAS/unknown-response invariants.

NO application code, production schema or authentication is exercised.
Requires POC_PG_DSN to a disposable loopback PostgreSQL database named "ontopoc".
Creates only schema m81_fk_preflight, refuses to overwrite existing schema, and drops it.
"""
from __future__ import annotations

import concurrent.futures
import hashlib
import json
import os
import uuid
from pathlib import Path
from urllib.parse import urlparse

import psycopg

SCHEMA = "m81_fk_preflight"
PKG = "research.m81"
HEAD = "equipment-inspections"
DSN = os.environ.get("POC_PG_DSN", "")


def require_disposable_target() -> None:
    parsed = urlparse(DSN)
    if (
        parsed.scheme not in ("postgresql", "postgres")
        or parsed.hostname not in ("localhost", "127.0.0.1", "::1")
        or parsed.path != "/ontopoc"
        or parsed.username != "postgres"
        or parsed.query
        or os.environ.get("M81_DISPOSABLE_PG17") != "1"
    ):
        raise RuntimeError(
            "M81_RESEARCH_UNSAFE_DSN: only explicit isolated loopback postgres/ontopoc "
            "with M81_DISPOSABLE_PG17=1 may be used"
        )


def open_db(**kwargs):
    return psycopg.connect(DSN, **kwargs)


def payload_hash(payload: dict) -> str:
    raw = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


class WriteFailure(Exception):
    pass


def save_update(idempotency_key: str, digest: str, expected_revision: int,
                package: str = PKG) -> tuple[int, str]:
    """Research-only transaction: read idem result before CAS, lock head, recheck."""
    with open_db() as db:
        query = f"""SELECT request_digest, committed_revision FROM {SCHEMA}.write_op
                    WHERE package_id=%s AND link_head=%s AND idempotency_key=%s"""
        args = (package, HEAD, idempotency_key)
        existing = db.execute(query, args).fetchone()
        if existing is not None:
            if existing[0] != digest:
                raise WriteFailure("IDEMPOTENCY_KEY_CONFLICT")
            return existing[1], "RECOVERED"
        head = db.execute(
            f"""SELECT current_revision FROM {SCHEMA}.link_head
                WHERE package_id=%s AND link_head=%s FOR UPDATE""",
            (package, HEAD),
        ).fetchone()
        if head is None:
            raise WriteFailure("NOT_AUTHORIZED_OR_NOT_FOUND")
        # Another request with the same key may have committed while we waited.
        existing = db.execute(query, args).fetchone()
        if existing is not None:
            if existing[0] != digest:
                raise WriteFailure("IDEMPOTENCY_KEY_CONFLICT")
            return existing[1], "RECOVERED"
        if head[0] != expected_revision:
            raise WriteFailure("BINDING_REVISION_CONFLICT")
        new_revision = head[0] + 1
        db.execute(
            f"""INSERT INTO {SCHEMA}.link_revision
                (package_id,link_head,revision,request_digest) VALUES (%s,%s,%s,%s)""",
            (package, HEAD, new_revision, digest),
        )
        db.execute(
            f"""INSERT INTO {SCHEMA}.write_op
                (package_id,link_head,idempotency_key,request_digest,committed_revision)
                VALUES (%s,%s,%s,%s,%s)""",
            (package, HEAD, idempotency_key, digest, new_revision),
        )
        db.execute(
            f"""UPDATE {SCHEMA}.link_head SET current_revision=%s
                WHERE package_id=%s AND link_head=%s""",
            (new_revision, package, HEAD),
        )
        return new_revision, "CREATED"


def require_failure(callback, expected: str) -> None:
    try:
        callback()
    except WriteFailure as e:
        if str(e) != expected:
            raise AssertionError(f"expected {expected}, got {e}") from e
        return
    raise AssertionError(f"expected failure {expected}")


def main() -> None:
    require_disposable_target()
    created = False
    version = None
    results = {"mode": "RESEARCH_POC_ONLY", "production_implemented": False,
               "source": "disposable_postgres_17", "checks": []}

    try:
        with open_db() as db:
            version = int(db.execute("SELECT current_setting('server_version_num')").fetchone()[0])
            if not (170000 <= version < 180000):
                raise RuntimeError("REQUIRES_POSTGRESQL_17")
            # Never touch an existing test schema or another application's tables.
            already = db.execute("SELECT to_regnamespace(%s)", (SCHEMA,)).fetchone()[0]
            if already is not None:
                raise RuntimeError("M81_RESEARCH_SCHEMA_ALREADY_EXISTS")
            db.execute(f"CREATE SCHEMA {SCHEMA}")
            created = True
            db.execute(f"""CREATE TABLE {SCHEMA}.equipment(
                equipment_code TEXT PRIMARY KEY, label TEXT NOT NULL)""")
            db.execute(f"""CREATE TABLE {SCHEMA}.inspection_lot(
                lot_key TEXT PRIMARY KEY, equipment_code TEXT NULL)""")
            db.execute(f"""INSERT INTO {SCHEMA}.equipment VALUES
                ('EQ-001','machine 1'),('EQ-002','machine 2'),('','blank key')""")
            db.execute(f"""INSERT INTO {SCHEMA}.inspection_lot VALUES
                ('LOT-11','EQ-001'),('LOT-12','EQ-001'),
                ('LOT-13',NULL),('LOT-14','EQ-MISSING'),('LOT-15','')""")
            db.execute(f"""CREATE TABLE {SCHEMA}.link_head(
                package_id TEXT NOT NULL, link_head TEXT NOT NULL,
                current_revision INTEGER NOT NULL, PRIMARY KEY(package_id,link_head))""")
            db.execute(f"""CREATE TABLE {SCHEMA}.link_revision(
                package_id TEXT NOT NULL, link_head TEXT NOT NULL,
                revision INTEGER NOT NULL, request_digest TEXT NOT NULL,
                PRIMARY KEY(package_id,link_head,revision))""")
            db.execute(f"""CREATE TABLE {SCHEMA}.write_op(
                package_id TEXT NOT NULL, link_head TEXT NOT NULL, idempotency_key UUID NOT NULL,
                request_digest TEXT NOT NULL, committed_revision INTEGER NOT NULL,
                PRIMARY KEY(package_id,link_head,idempotency_key))""")
            db.execute(f"INSERT INTO {SCHEMA}.link_head VALUES (%s,%s,1)", (PKG, HEAD))
            db.execute(f"""INSERT INTO {SCHEMA}.link_revision VALUES (%s,%s,1,%s)""",
                       (PKG, HEAD, "initial"))
        results["server_version_num"] = version

        # One source-side PG transaction: never combine separate statement snapshots.
        with open_db(autocommit=True) as db:
            db.execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
            isolation, readonly = db.execute(
                "SELECT current_setting('transaction_isolation'), "
                "current_setting('transaction_read_only')"
            ).fetchone()
            assert (isolation, readonly) == ("repeatable read", "on")
            out = db.execute(
                f"""SELECT l.lot_key FROM {SCHEMA}.equipment e
                    JOIN {SCHEMA}.inspection_lot l ON l.equipment_code=e.equipment_code
                    WHERE e.equipment_code=%s ORDER BY l.lot_key""", ("EQ-001",)
            ).fetchall()
            assert [r[0] for r in out] == ["LOT-11", "LOT-12"]
            zero = db.execute(
                f"""SELECT count(*) FROM {SCHEMA}.inspection_lot
                    WHERE equipment_code=%s""", ("EQ-002",)
            ).fetchone()[0]
            assert zero == 0
            nullable = db.execute(
                f"SELECT equipment_code FROM {SCHEMA}.inspection_lot WHERE lot_key='LOT-13'"
            ).fetchone()[0]
            assert nullable is None
            orphan = db.execute(
                f"""SELECT count(*) FROM {SCHEMA}.inspection_lot l
                    LEFT JOIN {SCHEMA}.equipment e
                      ON e.equipment_code=l.equipment_code
                    WHERE l.lot_key='LOT-14' AND l.equipment_code IS NOT NULL
                      AND e.equipment_code IS NULL"""
            ).fetchone()[0]
            assert orphan == 1
            blank = db.execute(
                f"""SELECT count(*) FROM {SCHEMA}.inspection_lot l
                    JOIN {SCHEMA}.equipment e ON e.equipment_code=l.equipment_code
                    WHERE l.lot_key='LOT-15'"""
            ).fetchone()[0]
            assert blank == 1, "empty TEXT must be treated as a real value, not SQL NULL"
            cardinality_conflict = db.execute(
                f"""SELECT count(*) FROM (
                      SELECT equipment_code FROM {SCHEMA}.inspection_lot
                      WHERE equipment_code IS NOT NULL
                      GROUP BY equipment_code HAVING count(DISTINCT lot_key)>1
                    ) violation"""
            ).fetchone()[0]
            assert cardinality_conflict == 1, "ONE_TO_ONE/TARGET FK must reject two LOTs"
            # Different per-type identity domains must not disable a genuine FK relationship.
            equipment_ref = (PKG, "equipment", "domain-equipment", "EQ-001")
            lot_ref = (PKG, "inspection-lot", "domain-lot", "EQ-001")
            assert equipment_ref != lot_ref
            assert len(out) == 2, "FK joins do not compare per-type identityDomainId"
            db.execute("COMMIT")
        results["checks"].extend([
            "ONE_TRANSACTION_READONLY_REPEATABLE_READ",
            "OUT_TWO_LINKS_AND_TRUE_ZERO",
            "IN_NULL_IS_NOT_ORPHAN",
            "SCOPE_ORPHAN_IS_PARTIAL_NOT_ZERO",
            "EMPTY_TEXT_IS_NOT_NULL",
            "ONE_TO_ONE_CARDINALITY_CONFLICT",
            "CROSS_TYPE_IDENTITY_DOMAIN_AND_FK_EQUALITY",
        ])

        # An explicitly READ ONLY PG transaction rejects writes even for this fixture.
        with open_db(autocommit=True) as db:
            db.execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
            try:
                db.execute(f"INSERT INTO {SCHEMA}.equipment VALUES ('BAD','bad')")
            except psycopg.errors.ReadOnlySqlTransaction:
                results["checks"].append("READ_ONLY_TX_REJECTS_WRITE")
            else:
                raise AssertionError("source research transaction unexpectedly writable")
            finally:
                db.execute("ROLLBACK")

        # Artificial boundary: detect >10k source rows before executing an unbounded Join.
        with open_db() as db:
            db.execute(f"CREATE TABLE {SCHEMA}.fanout_root(id INTEGER PRIMARY KEY)")
            db.execute(
                f"INSERT INTO {SCHEMA}.fanout_root SELECT generate_series(1,10001)")
            physical = len(db.execute(
                f"SELECT id FROM {SCHEMA}.fanout_root LIMIT %s", (10001,)
            ).fetchall())
            assert physical == 10001
            # No Join is issued; this is a research admission probe, NOT a production plan.
            results["checks"].append("PREJOIN_BOUNDARY_REJECTS_10001_ROWS")
        results["fanout_physical_rows"] = physical

        first_key = str(uuid.uuid4())
        digest = payload_hash({"fkOwner": "TARGET", "expectedRevision": 1})
        rev, status = save_update(first_key, digest, 1)
        assert (rev, status) == (2, "CREATED")
        recovered, status = save_update(first_key, digest, 1)
        assert (recovered, status) == (2, "RECOVERED")
        require_failure(
            lambda: save_update(first_key, payload_hash({"fkOwner": "SOURCE"}), 1),
            "IDEMPOTENCY_KEY_CONFLICT",
        )
        require_failure(
            lambda: save_update(str(uuid.uuid4()), digest, 1, "foreign.package"),
            "NOT_AUTHORIZED_OR_NOT_FOUND",
        )
        results["checks"].extend([
            "UPDATE_RESPONSE_UNKNOWN_RECOVERS_SAME_REVISION",
            "SAME_KEY_DIFFERENT_BODY_REJECTED",
            "CROSS_PACKAGE_KEY_CANNOT_RECOVER",
        ])

        def contender(_: int) -> str:
            try:
                save_update(str(uuid.uuid4()), payload_hash({"n": _}), 2)
                return "CREATED"
            except WriteFailure as e:
                return str(e)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            outcomes = sorted(pool.map(contender, range(2)))
        assert outcomes == ["BINDING_REVISION_CONFLICT", "CREATED"], outcomes
        with open_db() as db:
            head_revision = db.execute(
                f"SELECT current_revision FROM {SCHEMA}.link_head "
                "WHERE package_id=%s AND link_head=%s", (PKG, HEAD)
            ).fetchone()[0]
            n_revisions = db.execute(
                f"SELECT count(*) FROM {SCHEMA}.link_revision"
            ).fetchone()[0]
            n_operations = db.execute(
                f"SELECT count(*) FROM {SCHEMA}.write_op"
            ).fetchone()[0]
            assert (head_revision, n_revisions, n_operations) == (3, 3, 2)
        results["checks"].extend([
            "COMPETING_CAS_ONLY_ONE_WINNER",
            "IMMUTABLE_REVISIONS_AND_IDEMPOTENCY_SURVIVE_NEW_CONNECTIONS",
        ])
        results["cas_outcomes"] = outcomes
        results["head_revision"] = head_revision
        print(json.dumps(results, ensure_ascii=False, indent=2))
        out = Path(__file__).resolve().parent / "artifacts" / "m81_fk_preflight.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    finally:
        if created:
            with open_db() as db:
                db.execute(f"DROP SCHEMA IF EXISTS {SCHEMA} CASCADE")


if __name__ == "__main__":
    main()
