"""Native Ontology V0.2 contract review gates.

Only schema structure, deterministic reference normalization and local cross-reference
checks. Not an ontology inference engine, policy enforcement point, production compiler
or Action runtime authorization. No network calls or business-system writes.
Run: python -m unittest discover -s doc/design/tests -p test_native_v02.py -v
"""
from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

DOC = Path(__file__).resolve().parents[1]
SCHEMA = DOC / "schema"
EXAMPLES = DOC / "examples"

SCHEMAS = {
    "package": "ontology-package-v0.2.schema.json",
    "ast": "ontology-ast-v0.2.schema.json",
    "query": "objectset-query-v0.2.schema.json",
    "result": "objectset-result-v0.2.schema.json",
    "record": "semantic-execution-record-v0.2.schema.json",
}
EXAMPLE_FILES = {
    "package": "manufacturing-quality-v0.2.json",
    "ast": "manufacturing-quality-ast-v0.2.json",
    "query": "manufacturing-impact-query-v0.2.json",
    "result": "manufacturing-impact-partial-result-v0.2.json",
    "record": "manufacturing-action-unknown-v0.2.json",
}
KINDS = [
    ("ObjectType", "objectTypes"),
    ("LinkType", "linkTypes"),
    ("BindingDefinition", "bindings"),
    ("LinkBinding", "linkBindings"),
    ("ObjectSetDefinition", "objectSets"),
    ("FunctionDefinition", "functions"),
    ("RuleDefinition", "rules"),
    ("DecisionStrategy", "decisionStrategies"),
    ("PolicyDefinition", "policies"),
    ("ActionDefinition", "actions"),
]
ENTRY_TYPES = {
    "objectTypes", "linkTypes", "bindings", "linkBindings", "objectSets", "functions",
    "rules", "decisionStrategies", "policies", "actions",
}


def load(path: Path):
    # Reject duplicate JSON keys before validation. Last-wins would hide bad IDs.
    def reject_duplicate_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"DUPLICATE_JSON_KEY:{key}")
            result[key] = value
        return result
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicate_pairs)


SCHEMA_DATA = {k: load(SCHEMA / v) for k, v in SCHEMAS.items()}
EXAMPLE_DATA = {k: load(EXAMPLES / v) for k, v in EXAMPLE_FILES.items()}


def structure_errors(kind, data):
    validator = Draft202012Validator(SCHEMA_DATA[kind], format_checker=FormatChecker())
    return list(validator.iter_errors(data))


def require(condition, code):
    if not condition:
        raise ValueError(code)


def compile_ast(pkg):
    """Illustrative deterministic node conversion, NOT release digest / compiler."""
    nodes = []
    for kind, prop in KINDS:
        for item in sorted(pkg[prop], key=lambda x: x["id"].encode("utf-8")):
            nodes.append({"kind": kind, "definition": copy.deepcopy(item)})
    return {
        "astVersion": "0.2.0",
        "packageId": pkg["packageId"],
        "version": pkg["version"],
        "compilerProfile": "native-v0.2",
        "resolvedImports": copy.deepcopy(pkg["imports"]),
        "nodes": nodes,
    }


def semantic_validate_package(p):
    """Minimal local semantic gates; production must add registry/ACL/version gates."""
    require(not structure_errors("package", p), "STRUCTURE_FAILED")
    symbols = {}
    for collection in ENTRY_TYPES:
        ids = [x["id"] for x in p[collection]]
        require(len(set(ids)) == len(ids), f"DUPLICATE_ID:{collection}")
        symbols[collection] = {x["id"]: x for x in p[collection]}

    type_properties = {}
    property_ids = set()
    for t in p["objectTypes"]:
        ps = {x["id"]: x for x in t["properties"]}
        require(len(ps) == len(t["properties"]), "DUPLICATE_PROPERTY")
        require(not (set(ps) & property_ids), "CROSS_TYPE_PROPERTY_ID_COLLISION")
        property_ids.update(ps)
        require(t["primaryKeyRef"] in ps, "PRIMARY_KEY_MISSING")
        require(ps[t["primaryKeyRef"]]["required"], "PRIMARY_KEY_NOT_REQUIRED")
        require(ps[t["primaryKeyRef"]]["valueType"] in ("STRING", "INTEGER"), "PRIMARY_KEY_TYPE")
        type_properties[t["id"]] = ps

    for l in p["linkTypes"]:
        require(l["sourceTypeRef"] in type_properties, "LINK_SOURCE_UNKNOWN")
        require(l["targetTypeRef"] in type_properties, "LINK_TARGET_UNKNOWN")

    authority_claims = set()
    for b in p["bindings"]:
        require(b["objectTypeRef"] in type_properties, "BINDING_OBJECT_UNKNOWN")
        owned = type_properties[b["objectTypeRef"]]
        fields = [f["propertyRef"] for f in b["fieldMappings"]]
        require(len(fields) == len(set(fields)), "DUPLICATE_MAPPING_PROPERTY")
        require(set(fields) <= set(owned), "BINDING_PROPERTY_NOT_OWNED")
        for field in b["fieldMappings"]:
            if field["authority"] == "AUTHORITATIVE":
                claim = (b["objectTypeRef"], field["propertyRef"])
                require(claim not in authority_claims, "MULTIPLE_AUTHORITATIVE_SOURCES")
                authority_claims.add(claim)
        require(b["identity"]["strategy"] != "APPROVED_CROSS_SOURCE"
                or b["identity"].get("evidencePolicyRef"), "CROSS_SOURCE_WITHOUT_APPROVAL")
    for b in p["linkBindings"]:
        require(b["linkTypeRef"] in symbols["linkTypes"], "LINK_BINDING_UNKNOWN")

    for item in p["objectSets"]:
        require(item["rootTypeRef"] in type_properties, "OBJECTSET_ROOT_UNKNOWN")

    for f in p["functions"]:
        require(set(f["inputTypeRefs"]) <= set(type_properties), "FUNCTION_INPUT_UNKNOWN")
        require(set(f["declaredEffectTypeRefs"]) <= set(type_properties),
                "FUNCTION_EFFECT_TYPE_UNKNOWN")
    for strategy in p["decisionStrategies"]:
        require(set(strategy["ruleRefs"]) <= set(symbols["rules"]), "DECISION_RULE_UNKNOWN")
        f = symbols["functions"].get(strategy["functionRef"])
        require(f is not None and f["effectClass"] == "READ_ONLY",
                "DECISION_FUNCTION_MUST_BE_READ_ONLY")
    for a in p["actions"]:
        require(a["targetTypeRef"] in type_properties, "ACTION_TARGET_UNKNOWN")
        fn = symbols["functions"].get(a["functionRef"])
        require(fn is not None and fn["effectClass"] == "EDIT_PLAN",
                "ACTION_FUNCTION_MUST_BUILD_PLAN")
        require(a["targetTypeRef"] in fn["declaredEffectTypeRefs"],
                "ACTION_EFFECT_SCOPE_NOT_DECLARED")
        require(a["decisionRef"] in symbols["decisionStrategies"], "ACTION_DECISION_UNKNOWN")
        pols = [symbols["policies"].get(k) for k in a["policyRefs"]]
        require(all(x is not None for x in pols), "ACTION_POLICY_UNKNOWN")
        require(any(x["kind"] == "ACTION_AUTHORIZATION" for x in pols),
                "ACTION_AUTH_POLICY_MISSING")
        if a["approvalMode"] != "NONE":
            require(any(x["kind"] == "APPROVAL" for x in pols), "ACTION_APPROVAL_POLICY_MISSING")
        for effect in a["effects"]:
            require(effect["targetTypeRef"] in type_properties, "ACTION_EFFECT_TARGET_UNKNOWN")
            require(effect["targetTypeRef"] in fn["declaredEffectTypeRefs"],
                    "ACTION_EFFECT_SCOPE_NOT_DECLARED")
            props = type_properties[effect["targetTypeRef"]]
            require(set(effect.get("propertyRefs", [])) <= set(props),
                    "ACTION_EFFECT_PROPERTY_UNKNOWN")
    # Deliberately not implemented: namespace ownership, upstream release digests,
    # profile fidelity, policy decisions, approval signatures, materialized facts.
    return symbols, type_properties


def semantic_validate_query(pkg, q):
    require(not structure_errors("query", q), "QUERY_STRUCTURE_FAILED")
    _, props = semantic_validate_package(pkg)
    require(q["rootTypeRef"] in props, "QUERY_ROOT_UNKNOWN")
    if q.get("objectSetRef"):
        s = next((x for x in pkg["objectSets"] if x["id"] == q["objectSetRef"]), None)
        require(s is not None and s["rootTypeRef"] == q["rootTypeRef"], "QUERY_SET_ROOT_MISMATCH")
    links = {x["id"]: x for x in pkg["linkTypes"]}
    current = q["rootTypeRef"]
    terminal = False
    for step in q["steps"]:
        require(not terminal, "QUERY_AFTER_AGGREGATE")
        kind = step["kind"]
        if kind == "Filter":
            p = props[current].get(step["propertyRef"])
            require(p is not None, "QUERY_PROPERTY_WRONG_TYPE")
            require(p["searchable"], "QUERY_PROPERTY_NOT_SEARCHABLE")
            require((step["operator"] == "IN") == isinstance(step["value"], list),
                    "QUERY_FILTER_OPERATOR_VALUE_MISMATCH")
            values = step["value"] if isinstance(step["value"], list) else [step["value"]]
            expected = p["valueType"]
            if expected in ("STRING", "ENUM", "DATETIME"):
                require(all(isinstance(x, str) for x in values), "QUERY_FILTER_VALUE_TYPE")
            elif expected == "INTEGER":
                require(all(type(x) is int for x in values), "QUERY_FILTER_VALUE_TYPE")
            elif expected == "BOOLEAN":
                require(all(type(x) is bool for x in values), "QUERY_FILTER_VALUE_TYPE")
            elif expected == "DECIMAL":
                require(all(type(x) in (int, float) for x in values), "QUERY_FILTER_VALUE_TYPE")
            if expected == "ENUM":
                require(all(x in p["enumValues"] for x in values), "QUERY_ENUM_VALUE_UNKNOWN")
        elif kind == "Traverse":
            link = links.get(step["linkTypeRef"])
            require(link is not None, "QUERY_LINK_UNKNOWN")
            source, target = (link["sourceTypeRef"], link["targetTypeRef"])
            if step["direction"] == "IN":
                source, target = target, source
            require(current == source, "QUERY_LINK_DIRECTION_INVALID")
            current = target
        elif kind in ("Sort", "Project"):
            keys = [step["propertyRef"]] if kind == "Sort" else step["propertyRefs"]
            require(set(keys) <= set(props[current]), "QUERY_PROJECT_TYPE_MISMATCH")
        elif kind == "Aggregate":
            if "propertyRef" in step:
                p = props[current].get(step["propertyRef"])
                require(p is not None, "QUERY_AGGREGATE_PROPERTY_UNKNOWN")
                require(p["valueType"] in ("INTEGER", "DECIMAL"), "QUERY_AGGREGATE_NON_NUMERIC")
            terminal = True
    return current


class NativeV02ContractTests(unittest.TestCase):
    def setUp(self):
        self.pkg = copy.deepcopy(EXAMPLE_DATA["package"])
        self.ast = copy.deepcopy(EXAMPLE_DATA["ast"])
        self.query = copy.deepcopy(EXAMPLE_DATA["query"])
        self.record = copy.deepcopy(EXAMPLE_DATA["record"])
        self.result = copy.deepcopy(EXAMPLE_DATA["result"])

    def invalid_structure(self, kind, value):
        self.assertTrue(structure_errors(kind, value),
                        f"expected {kind} structure validation to reject")

    def invalid_semantic(self, pkg, code):
        with self.assertRaisesRegex(ValueError, code):
            semantic_validate_package(pkg)

    def test_all_four_schemas_well_formed(self):
        for name, schema in SCHEMA_DATA.items():
            with self.subTest(schema=name):
                Draft202012Validator.check_schema(schema)

    def test_four_golden_examples_validate(self):
        for kind, payload in EXAMPLE_DATA.items():
            with self.subTest(example=kind):
                self.assertEqual(structure_errors(kind, payload), [])

    def test_golden_semantics(self):
        semantic_validate_package(self.pkg)
        self.assertEqual(semantic_validate_query(self.pkg, self.query), "customer-order")

    def test_ast_golden_compiler_output_matches(self):
        self.assertEqual(compile_ast(self.pkg), self.ast)

    def test_ast_order_is_canonical_utf8(self):
        groups = {}
        for n in self.ast["nodes"]:
            groups.setdefault(n["kind"], []).append(n["definition"]["id"])
        for kind, ids in groups.items():
            with self.subTest(kind=kind):
                self.assertEqual(ids, sorted(ids, key=lambda x: x.encode("utf-8")))

    def test_duplicate_field_or_id(self):
        self.pkg["objectTypes"].append(copy.deepcopy(self.pkg["objectTypes"][0]))
        self.invalid_semantic(self.pkg, "DUPLICATE_ID")

    def test_missing_primary_key(self):
        self.pkg["objectTypes"][0]["primaryKeyRef"] = "not-a-field"
        self.invalid_semantic(self.pkg, "PRIMARY_KEY_MISSING")

    def test_cross_type_property_id_collision(self):
        target = next(t for t in self.pkg["objectTypes"] if t["id"] == "material-lot")
        next(p for p in target["properties"] if p["id"] == "source-quality-status")["id"] = "event-id"
        self.invalid_semantic(self.pkg, "CROSS_TYPE_PROPERTY_ID_COLLISION")

    def test_unknown_link_endpoint(self):
        self.pkg["linkTypes"][0]["sourceTypeRef"] = "missing-type"
        self.invalid_semantic(self.pkg, "LINK_SOURCE_UNKNOWN")

    def test_mapping_cannot_claim_unowned_property(self):
        binding = next(b for b in self.pkg["bindings"] if b["id"] == "qms-event-binding")
        binding["fieldMappings"][0]["propertyRef"] = "amount-cents"
        self.invalid_semantic(self.pkg, "BINDING_PROPERTY_NOT_OWNED")

    def test_cross_source_identity_without_evidence(self):
        self.pkg["bindings"][0]["identity"]["strategy"] = "APPROVED_CROSS_SOURCE"
        self.invalid_semantic(self.pkg, "CROSS_SOURCE_WITHOUT_APPROVAL")

    def test_conflicting_property_authority_rejected(self):
        clone = copy.deepcopy(self.pkg["bindings"][0])
        clone["id"] = "duplicate-qms-binding"
        clone["sourceRef"] = "second-qms"
        self.pkg["bindings"].append(clone)
        self.invalid_semantic(self.pkg, "MULTIPLE_AUTHORITATIVE_SOURCES")

    def test_external_action_effect_needs_executor(self):
        del self.pkg["actions"][0]["effects"][0]["executorRef"]
        self.invalid_structure("package", self.pkg)

    def test_unknown_rule_rejected(self):
        self.pkg["decisionStrategies"][0]["ruleRefs"].append("not-a-rule")
        self.invalid_semantic(self.pkg, "DECISION_RULE_UNKNOWN")

    def test_reasoning_function_cannot_authorize_action(self):
        self.pkg["actions"][0]["functionRef"] = "impact-risk"
        self.invalid_semantic(self.pkg, "ACTION_FUNCTION_MUST_BUILD_PLAN")

    def test_function_cannot_escalate_effect_scope(self):
        self.pkg["actions"][0]["effects"][0]["targetTypeRef"] = "customer-order"
        self.invalid_semantic(self.pkg, "ACTION_EFFECT_SCOPE_NOT_DECLARED")

    def test_action_needs_authorization_policy(self):
        self.pkg["actions"][0]["policyRefs"] = ["hold-approval"]
        self.invalid_semantic(self.pkg, "ACTION_AUTH_POLICY_MISSING")

    def test_action_high_risk_requires_approval_mode(self):
        self.pkg["actions"][0]["approvalMode"] = "NONE"
        self.invalid_structure("package", self.pkg)

    def test_read_only_function_cannot_declare_mutation(self):
        self.pkg["functions"][0]["declaredEffectTypeRefs"] = ["product-lot"]
        self.invalid_structure("package", self.pkg)

    def test_invalid_enum_missing_values(self):
        target = next(t for t in self.pkg["objectTypes"] if t["id"] == "quality-event")
        del next(p for p in target["properties"] if p["id"] == "inspection-status")["enumValues"]
        self.invalid_structure("package", self.pkg)

    def test_no_secrets_implicit_in_model(self):
        self.pkg["bindings"][0]["password"] = "plaintext"
        self.invalid_structure("package", self.pkg)

    def test_query_wrong_direction(self):
        self.query["steps"][1]["direction"] = "IN"
        with self.assertRaisesRegex(ValueError, "QUERY_LINK_DIRECTION_INVALID"):
            semantic_validate_query(self.pkg, self.query)

    def test_query_wrong_type_property(self):
        self.query["steps"][0]["propertyRef"] = "amount-cents"
        with self.assertRaisesRegex(ValueError, "QUERY_PROPERTY_WRONG_TYPE"):
            semantic_validate_query(self.pkg, self.query)

    def test_query_filter_in_operator_requires_array(self):
        self.query["steps"][0]["operator"] = "IN"
        with self.assertRaisesRegex(ValueError, "QUERY_FILTER_OPERATOR_VALUE_MISMATCH"):
            semantic_validate_query(self.pkg, self.query)

    def test_query_rejects_wrong_value_type(self):
        self.query["steps"][0]["value"] = 27
        with self.assertRaisesRegex(ValueError, "QUERY_FILTER_VALUE_TYPE"):
            semantic_validate_query(self.pkg, self.query)

    def test_query_rejects_invalid_enum(self):
        self.query["steps"][0]["value"] = "ALREADY_DELETED"
        with self.assertRaisesRegex(ValueError, "QUERY_ENUM_VALUE_UNKNOWN"):
            semantic_validate_query(self.pkg, self.query)

    def test_query_integer_literal_structure(self):
        self.query["steps"][0]["value"] = 123
        self.assertEqual(structure_errors("query", self.query), [])

    def test_query_rejects_arbitrary_sql(self):
        self.query["rawSql"] = "DELETE FROM customer_order"
        self.invalid_structure("query", self.query)

    def test_query_rejects_caller_supplied_authorization(self):
        self.query["authorizationContext"] = {"role": "admin"}
        self.invalid_structure("query", self.query)

    def test_query_budget_required(self):
        del self.query["limits"]["timeoutMs"]
        self.invalid_structure("query", self.query)

    def test_ast_discriminator_rejects_owl_first_node(self):
        self.ast["nodes"][0]["kind"] = "EntityType"
        self.invalid_structure("ast", self.ast)

    def test_success_requires_business_state_evidence(self):
        self.record["state"] = "VERIFIED_SUCCESS"
        self.invalid_structure("record", self.record)

    def test_transport_receipt_is_not_business_success(self):
        self.record["state"] = "VERIFIED_SUCCESS"
        self.record["externalReceipt"] = {
            "systemRef": "erp", "remoteCommandId": "erp-1", "receiptStatus": "ACCEPTED",
            "receivedAt": "2026-10-15T06:03:00Z"}
        self.invalid_structure("record", self.record)
        self.record["outcomeVerification"] = {
            "status": "CONFIRMED", "evidenceRef": "erp-state-proof-1",
            "verifiedAt": "2026-10-15T06:05:00Z"}
        self.assertEqual(structure_errors("record", self.record), [])

    def test_unknown_must_not_claim_verified_business_state(self):
        self.record["outcomeVerification"] = {
            "status": "CONFIRMED", "evidenceRef": "fabricated",
            "verifiedAt": "2026-10-15T06:05:00Z"}
        self.invalid_structure("record", self.record)

    def test_partial_result_cannot_claim_full_scope(self):
        self.result["answerScope"] = "REQUESTED_SCOPE"
        self.invalid_structure("result", self.result)

    def test_partial_result_cannot_claim_success(self):
        self.result["status"] = "SUCCESS"
        self.invalid_structure("result", self.result)

    def test_approximate_result_needs_error_bound(self):
        self.result["accuracy"] = "APPROXIMATE"
        self.invalid_structure("result", self.result)
        self.result["approximateErrorBound"] = 0.05
        self.assertEqual(structure_errors("result", self.result), [])

    def test_result_exactness_does_not_imply_completeness(self):
        self.assertEqual(self.result["accuracy"], "EXACT")
        self.assertEqual(self.result["completeness"], "PARTIAL")
        self.assertEqual(self.result["answerScope"], "KNOWN_SUBSET")

    def test_denied_authorization_cannot_report_unknown_dispatch(self):
        self.record["authorization"]["authorizationDecision"] = "DENY"
        self.invalid_structure("record", self.record)

    def test_denied_authorization_cannot_report_verified_success(self):
        self.record["authorization"]["authorizationDecision"] = "DENY"
        self.record["state"] = "VERIFIED_SUCCESS"
        self.record["externalReceipt"] = {
            "systemRef": "erp", "remoteCommandId": "erp-1", "receiptStatus": "ACCEPTED",
            "receivedAt": "2026-10-15T06:03:00Z"}
        self.record["outcomeVerification"] = {
            "status": "CONFIRMED", "evidenceRef": "erp-proof",
            "verifiedAt": "2026-10-15T06:05:00Z"}
        self.invalid_structure("record", self.record)

    def test_ast_definitions_do_not_drift_from_dsl(self):
        # Two independent schema files must keep an identical set of node definitions.
        for key, declaration in SCHEMA_DATA["package"]["$defs"].items():
            self.assertEqual(declaration, SCHEMA_DATA["ast"]["$defs"][key])

    def test_read_only_function_cannot_output_change_set(self):
        fn = next(x for x in self.pkg["functions"] if x["id"] == "impact-risk")
        fn["outputType"] = "CHANGE_SET"
        self.invalid_structure("package", self.pkg)

    def test_pending_approval_cannot_be_dispatched(self):
        self.record["authorization"]["authorizationDecision"] = "NEEDS_APPROVAL"
        self.invalid_structure("record", self.record)

    def test_receipt_confirmed_requires_accepted_receipt(self):
        self.record["state"] = "RECEIPT_CONFIRMED"
        self.invalid_structure("record", self.record)
        self.record["externalReceipt"] = {
            "systemRef": "erp", "remoteCommandId": "1",
            "receiptStatus": "REJECTED", "receivedAt": "2026-10-15T06:03:00Z"}
        self.invalid_structure("record", self.record)
        self.record["externalReceipt"]["receiptStatus"] = "ACCEPTED"
        self.assertEqual(structure_errors("record", self.record), [])

    def test_failed_query_result_has_error_without_fake_data(self):
        failed = load(EXAMPLES / "manufacturing-impact-failed-result-v0.2.json")
        self.assertEqual(structure_errors("result", failed), [])
        failed["aggregate"] = {"operation": "COUNT_DISTINCT", "value": 0}
        self.invalid_structure("result", failed)

    def test_failure_cannot_claim_known_success(self):
        failed = load(EXAMPLES / "manufacturing-impact-failed-result-v0.2.json")
        failed["status"] = "SUCCESS"
        self.invalid_structure("result", failed)

    def test_v01_is_not_silently_accepted_as_v02(self):
        old = load(EXAMPLES / "manufacturing-quality-v0.1.json")
        self.invalid_structure("package", old)
        self.assertTrue(old["axioms"])
        self.assertIn("namespaceIri", old)

    def test_v01_and_v02_remain_distinct_contracts(self):
        old = load(EXAMPLES / "manufacturing-quality-v0.1.json")
        self.assertEqual(old["schemaVersion"], "0.1.0")
        self.assertEqual(self.pkg["schemaVersion"], "0.2.0")


if __name__ == "__main__":
    unittest.main()
