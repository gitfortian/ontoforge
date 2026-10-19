"""R2 review-only structural contract tests; M8 runtime is not implemented."""
from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

DOC = Path(__file__).resolve().parents[1]
DIR = DOC / "candidates" / "m8-r2"
Q = json.loads((DIR / "local-query-v2.candidate.schema.json").read_text(encoding="utf-8"))
B = json.loads((DIR / "local-link-binding-v1.candidate.schema.json").read_text(encoding="utf-8"))
R = json.loads((DIR / "local-result-v2.candidate.schema.json").read_text(encoding="utf-8"))
OLD_Q = json.loads((DOC / "schema" / "local-query-v1.schema.json").read_text(encoding="utf-8"))

SNAP = "a5e2cbcb-3d16-49a8-9d8f-3f4cbba7b3ab"
SOURCE_BIND = "1e0d6e7d-2d1a-4ed5-9e80-f45eecdbb3e8"
TARGET_BIND = "2e0d6e7d-2d1a-4ed5-9e80-f45eecdbb3e8"
LINK_BIND = "3e0d6e7d-2d1a-4ed5-9e80-f45eecdbb3e8"
EXEC = "cd6c7e89-8a8b-489e-9b17-92e29a4f3ef0"
READ = "1dfb4f48-c86a-47e6-8fb7-7a190cb8b9ce"
DOMAIN = "9dfb4f48-c86a-47e6-8fb7-7a190cb8b9ce"
PKG = "manufacturing.quality"
FMT = FormatChecker()


def validator(schema):
    return Draft202012Validator(schema, format_checker=FMT)


def valid(schema, value):
    return not list(validator(schema).iter_errors(value))


def binding_request():
    return {
        "contractVersion": "local-link-binding-v1",
        "modelSourceRef": {"kind": "LOCAL_SNAPSHOT", "snapshotId": SNAP},
        "packageId": PKG,
        "linkTypeId": "equipment-inspections",
        "sourceBindingRef": {"bindingId": SOURCE_BIND, "revision": 2},
        "targetBindingRef": {"bindingId": TARGET_BIND, "revision": 3},
        "mode": "FOREIGN_KEY", "fkOwner": "TARGET",
        "fkSourceColumn": "equipment_code", "expectedRevision": 0,
        "idempotencyKey": EXEC,
        "expectedSchemaFingerprints": {"source": "hash-source", "target": "hash-target"},
        "validationScope": {"kind": "VALIDATION_SCOPE", "coverage": "FULL"},
    }


def object_ref(kind="equipment", key="EQ-001"):
    return {
        "packageId": PKG, "objectTypeId": kind, "identityDomainId": DOMAIN,
        "key": {"valueType": "STRING", "value": key},
    }


def traverse_query():
    return {
        "queryVersion": "local-query-v2",
        "rootTypeRef": {
            "sourceRef": {"kind": "LOCAL_SNAPSHOT", "snapshotId": SNAP},
            "packageId": PKG, "objectTypeId": "equipment",
        },
        "rootBindingRef": {"bindingId": SOURCE_BIND, "revision": 2},
        "rootSelector": {"kind": "OBJECT_REF", "objectRef": object_ref()},
        "steps": [
            {"kind": "TRAVERSE", "linkTypeRef": "equipment-inspections",
             "linkBindingRef": {"bindingId": LINK_BIND, "revision": 1},
             "direction": "OUT", "maxDepth": 1},
            {"kind": "PAGE", "pageSize": 25},
        ],
    }


def result():
    bindings = [
        {"kind": "OBJECT", "bindingId": SOURCE_BIND, "revision": 2,
         "digest": "a" * 64, "sourceId": "local-pg-sandbox"},
        {"kind": "OBJECT", "bindingId": TARGET_BIND, "revision": 3,
         "digest": "b" * 64, "sourceId": "local-pg-sandbox"},
        {"kind": "LINK", "bindingId": LINK_BIND, "revision": 1,
         "digest": "c" * 64, "sourceId": "local-pg-sandbox"},
    ]
    source = {
        "id": READ, "role": "TARGET", "sourceId": "local-pg-sandbox",
        "schema": "public", "table": "m8_inspection_lot", "columns": ["equipment_code"],
        "schemaFingerprint": "fingerprint-1", "transactionIsolation": "REPEATABLE_READ",
        "checkpoint": {"status": "UNKNOWN", "reason": "No business watermark"},
        "startedAt": "2026-10-19T10:00:00Z", "completedAt": "2026-10-19T10:00:01Z",
    }
    source_endpoint = dict(source)
    source_endpoint.update({
        "id": "2dfb4f48-c86a-47e6-8fb7-7a190cb8b9ce", "role": "SOURCE",
        "table": "m8_equipment", "columns": ["equipment_code"],
        "schemaFingerprint": "fingerprint-2",
    })
    return {
        "status": "SUCCESS", "resultKind": "PAGE", "executionId": EXEC,
        "executedAt": "2026-10-19T10:00:01Z",
        "queryVersion": "local-query-v2",
        "rootTypeRef": {"kind": "LOCAL_SNAPSHOT", "snapshotId": SNAP,
                        "packageId": PKG, "objectTypeId": "equipment"},
        "outputTypeRef": {"kind": "LOCAL_SNAPSHOT", "snapshotId": SNAP,
                          "packageId": PKG, "objectTypeId": "inspection-lot"},
        "versionLock": {
            "snapshotId": SNAP, "payloadSha256": "d" * 64,
            "bindingVersions": bindings,
            "sourceSchemaFingerprints": [
                {"role": "SOURCE", "sourceId": "local-pg-sandbox", "schema": "public",
                 "table": "m8_equipment", "schemaFingerprint": "fingerprint-2"},
                {"role": "TARGET", "sourceId": "local-pg-sandbox", "schema": "public",
                 "table": "m8_inspection_lot", "schemaFingerprint": "fingerprint-1"},
            ],
        },
        "bindingVersions": bindings, "sourceReadRefs": [source_endpoint, source],
        "queryDigest": "e" * 64, "resultSnapshotRef": "local-result:" + EXEC,
        "expiresAt": "2026-10-19T10:05:01Z",
        "quality": {
            "accuracy": "EXACT", "completeness": "COMPLETE",
            "freshness": "UNKNOWN", "answerScope": "REQUESTED_SCOPE",
            "scopeKind": "TRAVERSAL_SCOPE", "knownOrphanCount": 0,
        },
        "budget": {
            "physicalRows": 5, "joinCandidates": 2, "distinctObjects": 2,
            "distinctLinks": 2, "sourceBytes": 512, "serializedResultBytes": 1500,
            "maxPhysicalRows": 10000, "maxJoinCandidates": 10000,
            "maxSourceBytes": 8388608, "maxSerializedResultBytes": 8388608,
            "queryTimeoutSeconds": 3,
        },
        "objects": [{"objectRef": object_ref("inspection-lot", "LOT-11"), "properties": []}],
        "links": [{
            "linkTypeId": "equipment-inspections", "linkBindingId": LINK_BIND,
            "linkBindingRevision": 1, "source": object_ref(),
            "target": object_ref("inspection-lot", "LOT-11"),
            "sourceReadRefs": [source_endpoint["id"], READ],
        }],
        "objectCount": 1, "linkCount": 1,
        "pathCount": 1, "pageSize": 25, "pageNumber": 1,
    }


class M8R2CandidateContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for schema in (Q, B, R):
            Draft202012Validator.check_schema(schema)

    def test_binding_create_requires_idempotency_and_snapshot(self):
        good = binding_request()
        self.assertTrue(valid(B, good))
        missing = copy.deepcopy(good)
        missing.pop("idempotencyKey")
        self.assertFalse(valid(B, missing))
        old_source = copy.deepcopy(good)
        old_source["modelSourceRef"]["kind"] = "SCHEMA_RELEASE"
        self.assertFalse(valid(B, old_source))
        arbitrary_sql = copy.deepcopy(good)
        arbitrary_sql["sql"] = "SELECT 1"
        self.assertFalse(valid(B, arbitrary_sql))
        unsafe_column = copy.deepcopy(good)
        unsafe_column["fkSourceColumn"] = "code; DROP TABLE x"
        self.assertFalse(valid(B, unsafe_column))

    def test_versioned_revision_accepts_cas_and_no_latest(self):
        good = binding_request()
        good["expectedRevision"] = 2
        good.pop("idempotencyKey")
        self.assertTrue(valid(B, good))
        bad = copy.deepcopy(good)
        bad["sourceBindingRef"].pop("revision")
        self.assertFalse(valid(B, bad))

    def test_traverse_one_hop_and_lowered_budgets(self):
        good = traverse_query()
        self.assertTrue(valid(Q, good), list(validator(Q).iter_errors(good)))
        good["limits"] = {"maxJoinCandidates": 40, "timeoutSeconds": 1}
        self.assertTrue(valid(Q, good))
        bad = copy.deepcopy(good)
        bad["limits"]["maxJoinCandidates"] = 10001
        self.assertFalse(valid(Q, bad))
        bad = copy.deepcopy(good)
        bad["steps"][0]["maxDepth"] = 2
        self.assertFalse(valid(Q, bad))
        bad = copy.deepcopy(good)
        bad["steps"].insert(1, copy.deepcopy(bad["steps"][0]))
        self.assertFalse(valid(Q, bad))

    def test_bad_typed_literals_unknown_ops_and_raw_sql_rejected(self):
        good = traverse_query()
        good["steps"].insert(1, {
            "kind": "FILTER", "scope": "TARGET",
            "conditions": [{
                "propertyRef": "lot-code", "operator": "EQ",
                "literal": {"valueType": "STRING", "value": "LOT-11"},
            }],
        })
        self.assertTrue(valid(Q, good))
        bad = copy.deepcopy(good)
        bad["steps"][1]["conditions"][0]["literal"] = {"valueType": "TEXT", "value": "LOT-11"}
        self.assertFalse(valid(Q, bad))
        bad = copy.deepcopy(good)
        bad["steps"][1]["conditions"][0]["literal"] = {"valueType": "BOOLEAN", "value": "true"}
        self.assertFalse(valid(Q, bad))
        bad = copy.deepcopy(good)
        bad["steps"][1]["conditions"][0].pop("literal")
        self.assertFalse(valid(Q, bad))
        bad = copy.deepcopy(good)
        bad["rawSql"] = "select * from arbitrary_schema.table"
        self.assertFalse(valid(Q, bad))
        self.assertFalse(valid(OLD_Q, traverse_query()))

    def test_root_filter_is_only_in_selector_and_target_filter_is_separate(self):
        good = traverse_query()
        good["rootSelector"] = {
            "kind": "BOUNDED_ROOT_FILTER",
            "conditions": [{"propertyRef": "equipment-code", "operator": "EQ",
                            "literal": {"valueType": "STRING", "value": "EQ-001"}}],
        }
        self.assertTrue(valid(Q, good))
        bad = copy.deepcopy(good)
        bad["steps"].insert(0, {
            "kind": "FILTER", "scope": "ROOT",
            "conditions": [{"propertyRef": "equipment-code", "operator": "EQ",
                            "literal": {"valueType": "STRING", "value": "EQ-001"}}],
        })
        self.assertFalse(valid(Q, bad))

    def test_result_quality_is_explicit_and_failure_has_no_payload(self):
        good = result()
        self.assertTrue(valid(R, good), list(validator(R).iter_errors(good)))
        partial = copy.deepcopy(good)
        partial["quality"]["completeness"] = "PARTIAL"
        partial["quality"]["answerScope"] = "KNOWN_SUBSET"
        partial["quality"]["knownOrphanCount"] = 1
        self.assertTrue(valid(R, partial), list(validator(R).iter_errors(partial)))
        false_partial = copy.deepcopy(partial)
        false_partial["quality"]["knownOrphanCount"] = 0
        self.assertFalse(valid(R, false_partial))
        missing_source = copy.deepcopy(good)
        missing_source["sourceReadRefs"] = missing_source["sourceReadRefs"][1:]
        self.assertFalse(valid(R, missing_source))
        missing_schema = copy.deepcopy(good)
        missing_schema["versionLock"]["sourceSchemaFingerprints"] = [
            fp for fp in missing_schema["versionLock"]["sourceSchemaFingerprints"]
            if fp["role"] == "TARGET"
        ]
        self.assertFalse(valid(R, missing_schema))
        missing_link_version = copy.deepcopy(good)
        missing_link_version["bindingVersions"] = missing_link_version["bindingVersions"][:2]
        self.assertFalse(valid(R, missing_link_version))
        false_fresh = copy.deepcopy(good)
        false_fresh["quality"]["freshness"] = "FRESH"
        self.assertFalse(valid(R, false_fresh))
        failure = {
            "status": "FAILED", "executionId": EXEC, "queryVersion": "local-query-v2",
            "code": "SOURCE_TIMEOUT", "message": "Source read failed",
        }
        self.assertTrue(valid(R, failure))
        failure["objects"] = []
        self.assertFalse(valid(R, failure))

    def test_m8_2_m8_3_and_native_release_are_out_of_contract(self):
        good = traverse_query()
        good["steps"][0]["linkBindingRef"]["revision"] = 0
        self.assertFalse(valid(Q, good))
        good = traverse_query()
        good["rootTypeRef"]["sourceRef"]["kind"] = "SCHEMA_RELEASE"
        self.assertFalse(valid(Q, good))
        good = binding_request()
        good["mode"] = "BRIDGE_TABLE"
        self.assertFalse(valid(B, good))


if __name__ == "__main__":
    unittest.main(verbosity=2)
