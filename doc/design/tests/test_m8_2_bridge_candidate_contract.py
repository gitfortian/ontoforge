"""M8.2 REVIEW-ONLY JSON Schema positive/negative proof; no production server.

Real PostgreSQL, source permissions and SourceReadRef ID cross-validation
remain independent runtime acceptance gates C01-C12; never claim PG E2E PASS.
"""
from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

import test_m8_r2_candidate_contract as m81

ROOT = Path(__file__).resolve().parents[1]
DIR = ROOT / "candidates" / "m8-2"
BRIDGE = json.loads((DIR / "local-link-binding-v2-bridge.candidate.schema.json").read_text(encoding="utf-8"))
RESULT = json.loads((DIR / "local-result-v2-bridge.candidate.schema.json").read_text(encoding="utf-8"))
FMT = FormatChecker()
BRIDGE_READ = "4dfb4f48-c86a-47e6-8fb7-7a190cb8b9ce"


def valid(schema, instance):
    return not list(Draft202012Validator(schema, format_checker=FMT).iter_errors(instance))


def request():
    old = m81.binding_request()
    old["contractVersion"] = "local-link-binding-v2"
    old["mode"] = "BRIDGE_TABLE"
    old["sourceBindingRef"]["digest"] = "a" * 64
    old["targetBindingRef"]["digest"] = "b" * 64
    old.pop("fkOwner")
    old.pop("fkSourceColumn")
    old["bridgeSourceRef"] = {
        "sourceId": "local-pg-sandbox", "schema": "public",
        "table": "maintenance_order_equipment",
        "sourceFkColumn": "work_order_no", "targetFkColumn": "equipment_code",
    }
    old["expectedSchemaFingerprints"] = {
        "source": "c" * 64, "target": "d" * 64, "bridge": "e" * 64,
    }
    return old


def result():
    res = m81.result()
    res["linkMappingMode"] = "BRIDGE_TABLE"
    res["bridgeDuplicatePairRowCount"] = 1
    bridge_read = copy.deepcopy(res["sourceReadRefs"][0])
    bridge_read.update({
        "id": BRIDGE_READ,
        "role": "BRIDGE",
        "table": "maintenance_order_equipment",
        "columns": ["work_order_no", "equipment_code"],
        "schemaFingerprint": "bridge-fingerprint",
    })
    res["sourceReadRefs"].append(bridge_read)
    res["versionLock"]["sourceSchemaFingerprints"].append({
        "role": "BRIDGE", "sourceId": "local-pg-sandbox",
        "schema": "public", "table": "maintenance_order_equipment",
        "schemaFingerprint": "bridge-fingerprint",
    })
    res["links"][0]["sourceReadRefs"].append(BRIDGE_READ)
    return res


class BridgeCandidateContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        Draft202012Validator.check_schema(BRIDGE)
        Draft202012Validator.check_schema(RESULT)

    def test_bridge_create_strict_and_no_v1_contract_regression(self):
        good = request()
        self.assertTrue(valid(BRIDGE, good), list(Draft202012Validator(BRIDGE, format_checker=FMT).iter_errors(good)))
        self.assertFalse(valid(m81.B, good))
        self.assertTrue(valid(m81.B, m81.binding_request()))

    def test_bridge_request_fails_closed_on_fk_alias_or_sql(self):
        for field, value in [
            ("fkOwner", "TARGET"), ("fkSourceColumn", "work_order_no"),
            ("referencedIdentityColumn", "equipment_code"),
            ("sql", "SELECT * FROM secret"),
        ]:
            with self.subTest(field=field):
                bad = request()
                bad[field] = value
                self.assertFalse(valid(BRIDGE, bad))

    def test_bridge_must_have_both_endpoint_revision_digests_and_three_fingerprints(self):
        good = request()
        for mutation in ("dropSourceRevision", "dropTargetDigest", "dropBridgeFingerprint",
                         "dropOperationKey", "dropBridgeFk", "wrongSnapshot"):
            bad = copy.deepcopy(good)
            if mutation == "dropSourceRevision":
                bad["sourceBindingRef"].pop("revision")
            elif mutation == "dropTargetDigest":
                bad["targetBindingRef"].pop("digest")
            elif mutation == "dropBridgeFingerprint":
                bad["expectedSchemaFingerprints"].pop("bridge")
            elif mutation == "dropOperationKey":
                bad.pop("idempotencyKey")
            elif mutation == "dropBridgeFk":
                bad["bridgeSourceRef"].pop("targetFkColumn")
            else:
                bad["modelSourceRef"]["kind"] = "SCHEMA_RELEASE"
            with self.subTest(mutation=mutation):
                self.assertFalse(valid(BRIDGE, bad))

    def test_bridge_rejects_unsafe_column_and_incomplete_scope(self):
        good = request()
        bad = copy.deepcopy(good)
        bad["bridgeSourceRef"]["sourceFkColumn"] = 'id"; DROP TABLE stuff;--'
        self.assertFalse(valid(BRIDGE, bad))
        bad = copy.deepcopy(good)
        bad["validationScope"]["coverage"] = "SAMPLE"
        self.assertFalse(valid(BRIDGE, bad))
        bad = copy.deepcopy(good)
        bad["sourceBindingRef"]["digest"] = "unsigned"
        self.assertFalse(valid(BRIDGE, bad))

    def test_bridge_result_requires_all_three_source_roles_and_mapping_marker(self):
        good = result()
        self.assertTrue(valid(RESULT, good), list(Draft202012Validator(RESULT, format_checker=FMT).iter_errors(good)))
        self.assertFalse(valid(m81.R, good))
        self.assertTrue(valid(m81.R, m81.result()))

        mutations = {
            "missingRead": lambda x: x["sourceReadRefs"].pop(),
            "missingFingerprint": lambda x: x["versionLock"]["sourceSchemaFingerprints"].pop(),
            "missingLinkBridgeRead": lambda x: x["links"][0]["sourceReadRefs"].pop(),
            "wrongMappingMode": lambda x: x.update(linkMappingMode="FOREIGN_KEY"),
            "negativeDuplicateCount": lambda x: x.update(bridgeDuplicatePairRowCount=-1),
            "missingMappingMode": lambda x: x.pop("linkMappingMode"),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                bad = copy.deepcopy(good)
                mutate(bad)
                self.assertFalse(valid(RESULT, bad))

    def test_bridge_result_rejects_fake_fourth_role_or_duplicate_role(self):
        good = result()
        bad = copy.deepcopy(good)
        bad["sourceReadRefs"].append(copy.deepcopy(bad["sourceReadRefs"][0]))
        self.assertFalse(valid(RESULT, bad))
        bad = copy.deepcopy(good)
        bad["sourceReadRefs"][2]["role"] = "DANGEROUS_OTHER_TABLE"
        self.assertFalse(valid(RESULT, bad))

    def test_old_one_hop_and_failures_remain_strict(self):
        query = m81.traverse_query()
        self.assertTrue(valid(m81.Q, query))
        bad = copy.deepcopy(query)
        bad["steps"].insert(1, copy.deepcopy(bad["steps"][0]))
        self.assertFalse(valid(m81.Q, bad))
        failure = {
            "status": "FAILED", "executionId": m81.EXEC,
            "queryVersion": "local-query-v2", "code": "SOURCE_NOT_ALLOWED",
            "message": "Source unavailable",
        }
        self.assertTrue(valid(RESULT, failure))
        failure["sourceReadRefs"] = []
        self.assertFalse(valid(RESULT, failure))


if __name__ == "__main__":
    unittest.main(verbosity=2)
