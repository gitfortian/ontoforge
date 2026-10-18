"""Structural and negative contract tests for the isolated M7 local-query-v1 profile."""
from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

DOC = Path(__file__).resolve().parents[1]
SCHEMA_DIR = DOC / "schema"
QUERY_SCHEMA = json.loads((SCHEMA_DIR / "local-query-v1.schema.json").read_text(encoding="utf-8"))
RESULT_SCHEMA = json.loads((SCHEMA_DIR / "local-result-v1.schema.json").read_text(encoding="utf-8"))
FORMATS = FormatChecker()


def valid(validator, value):
    return not list(validator.iter_errors(value))


def query_base():
    return {
        "queryVersion": "local-query-v1",
        "rootTypeRef": {
            "sourceRef": {"kind": "LOCAL_SNAPSHOT", "snapshotId": "a5e2cbcb-3d16-49a8-9d8f-3f4cbba7b3ab"},
            "packageId": "manufacturing.procurement",
            "objectTypeId": "supplier",
        },
        "bindingRef": {"bindingId": "1e0d6e7d-2d1a-4ed5-9e80-f45eecdbb3e8", "revision": 2},
        "steps": [
            {"kind": "FILTER", "conditions": [
                {"propertyRef": "supplier-rating", "operator": "GTE",
                 "literal": {"valueType": "DECIMAL", "value": "4.5"}}
            ]},
            {"kind": "SORT", "fields": [{"propertyRef": "supplier-code", "direction": "ASC"}]},
            {"kind": "PROJECT", "propertyRefs": ["supplier-code", "supplier-rating"]},
            {"kind": "PAGE", "pageSize": 25},
        ],
    }


def result_base():
    source_ref = {"kind": "LOCAL_SNAPSHOT", "snapshotId": "a5e2cbcb-3d16-49a8-9d8f-3f4cbba7b3ab"}
    binding_versions = [{
        "bindingId": "1e0d6e7d-2d1a-4ed5-9e80-f45eecdbb3e8",
        "revision": 2, "digest": "binding-digest", "sourceId": "local-pg-sandbox", "kind": "OBJECT",
    }]
    return {
        "status": "SUCCESS",
        "executionId": "cd6c7e89-8a8b-489e-9b17-92e29a4f3ef0",
        "executedAt": "2026-10-18T10:00:00Z",
        "queryVersion": "local-query-v1",
        "typeSourceRef": source_ref,
        "payloadSha256": "a" * 64,
        "accessMode": "LOCAL_ISOLATED",
        "bindingVersions": binding_versions,
        "versionLock": {
            "typeSourceRef": source_ref,
            "payloadSha256": "a" * 64,
            "bindingVersions": binding_versions,
            "sourceSchemaFingerprints": [{
                "sourceId": "local-pg-sandbox", "schema": "public",
                "table": "m5_supplier_source", "schemaFingerprint": "fingerprint",
            }],
        },
        "sourceReadRef": {
            "id": "1dfb4f48-c86a-47e6-8fb7-7a190cb8b9ce",
            "sourceId": "local-pg-sandbox", "schema": "public", "table": "m5_supplier_source",
            "columns": ["vendor_code", "rating"], "schemaFingerprint": "fingerprint",
            "startedAt": "2026-10-18T10:00:00Z", "completedAt": "2026-10-18T10:00:01Z",
            "transactionIsolation": "REPEATABLE_READ",
            "checkpoint": {"status": "UNKNOWN", "reason": "No verified business freshness watermark"},
            "consistencyNote": "Bounded frozen result; not a source MVCC snapshot.",
        },
        "queryDigest": "b" * 64,
        "resultSnapshotRef": "local-result:cd6c7e89-8a8b-489e-9b17-92e29a4f3ef0",
        "expiresAt": "2026-10-18T10:05:00Z",
        "quality": {"accuracy": "EXACT", "completeness": "COMPLETE", "freshness": "UNKNOWN", "answerScope": "REQUESTED_SCOPE"},
        "budget": {
            "physicalRows": 5, "distinctObjects": 5, "sourceBytes": 120, "serializedResultBytes": 300,
            "maxPhysicalRows": 10000, "maxSourceBytes": 8388608,
            "maxSerializedResultBytes": 8388608, "queryTimeoutSeconds": 3,
        },
    }


class LocalQueryV1ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        Draft202012Validator.check_schema(QUERY_SCHEMA)
        Draft202012Validator.check_schema(RESULT_SCHEMA)
        cls.query_validator = Draft202012Validator(QUERY_SCHEMA, format_checker=FORMATS)
        cls.result_validator = Draft202012Validator(RESULT_SCHEMA, format_checker=FORMATS)

    def test_query_schema_accepts_typed_page_and_aggregate(self):
        self.assertTrue(valid(self.query_validator, query_base()))
        aggregate = query_base()
        aggregate["steps"] = [{"kind": "AGGREGATE", "operation": "SUM", "propertyRef": "supplier-rating"}]
        self.assertTrue(valid(self.query_validator, aggregate))

    def test_query_schema_rejects_fake_release_raw_sql_and_budget_escalation(self):
        query = query_base()
        query["schemaReleaseRef"] = "sha256:" + "a" * 64
        self.assertFalse(valid(self.query_validator, query))
        query = query_base()
        query["rawSql"] = "select * from public.m5_supplier_source"
        self.assertFalse(valid(self.query_validator, query))
        query = query_base()
        query["steps"][-1]["pageSize"] = 51
        self.assertFalse(valid(self.query_validator, query))

    def test_query_schema_rejects_wrong_literal_type_and_malformed_root_ref(self):
        query = query_base()
        query["steps"][0]["conditions"][0]["literal"] = {"valueType": "BOOLEAN", "value": "true"}
        self.assertFalse(valid(self.query_validator, query))
        query = query_base()
        query["rootTypeRef"]["sourceRef"]["kind"] = "SCHEMA_RELEASE"
        self.assertFalse(valid(self.query_validator, query))
        query = query_base()
        del query["bindingRef"]["revision"]
        self.assertFalse(valid(self.query_validator, query))

    def test_result_schema_accepts_page_aggregate_and_unknown_freshness(self):
        page = result_base()
        page.update({"objects": [], "totalCount": 0, "pageSize": 25, "pageNumber": 1})
        self.assertTrue(valid(self.result_validator, page), list(self.result_validator.iter_errors(page)))
        aggregate = result_base()
        aggregate["aggregate"] = {"operation": "COUNT_DISTINCT", "valueType": "INTEGER", "value": "0", "matchedObjects": 0}
        self.assertTrue(valid(self.result_validator, aggregate), list(self.result_validator.iter_errors(aggregate)))

    def test_failed_result_cannot_smuggle_partial_payload(self):
        failed = {
            "status": "FAILED", "executionId": "cd6c7e89-8a8b-489e-9b17-92e29a4f3ef0",
            "code": "QUERY_BUDGET_EXCEEDED", "message": "No partial result.",
            "quality": {"accuracy": "UNKNOWN", "completeness": "UNKNOWN", "freshness": "UNKNOWN", "answerScope": "REQUESTED_SCOPE"},
        }
        self.assertTrue(valid(self.result_validator, failed))
        failed["objects"] = []
        self.assertFalse(valid(self.result_validator, failed))


if __name__ == "__main__":
    unittest.main(verbosity=2)
