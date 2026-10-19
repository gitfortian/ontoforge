"""M8.1 production React golden story on a disposable real PG17 database + real JWT.

No mocked fetches. The browser itself validates, writes LinkBinding, traverses,
sorts, projects and aggregates. Sources are CI-only (scripts/m8-onehop-e2e-source.sql).
"""
import json
import os
import unittest
from pathlib import Path
from urllib.parse import quote
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from playwright.sync_api import sync_playwright
from b0_fixture import CONFIG, assigned_package, headers

BASE = "http://127.0.0.1:5173"
API = "http://127.0.0.1:8080/api"


def api(path, method="GET", body=None):
    payload = json.dumps(body).encode("utf-8") if body is not None else None
    req = Request(API + path, data=payload, method=method,
                  headers={"Content-Type": "application/json", **headers()})
    with urlopen(req, timeout=15) as response:
        return json.load(response)


def prop(key, name, value_type="STRING"):
    return {"id": key, "apiName": name, "valueType": value_type,
            "required": key in ("cell-code", "event-code"),
            "searchable": True, "classification": "INTERNAL"}


def build_source(package_id):
    draft = api("/model/drafts", "POST", {"packageId": package_id})
    content = {
        "editorSchemaVersion": "0.1.0",
        "objectTypes": [
            {"id": "factory-cell", "apiName": "FactoryCell",
             "primaryKeyRef": "cell-code",
             "properties": [prop("cell-code", "cellCode"), prop("cell-name", "cellName"),
                            prop("cell-site", "site"), prop("cell-cost", "cost", "DECIMAL")]},
            {"id": "quality-event", "apiName": "QualityEvent",
             "primaryKeyRef": "event-code",
             "properties": [prop("event-code", "eventCode"), prop("cell-ref", "cellRef"),
                            prop("event-status", "status"),
                            prop("event-amount", "amount", "DECIMAL")]}
        ],
        "linkTypes": [{"id": "cell-quality", "apiName": "cellQuality",
                       "sourceTypeRef": "factory-cell", "targetTypeRef": "quality-event",
                       "cardinality": "ONE_TO_MANY", "evidenceRequired": True}]
    }
    saved = api("/model/drafts/" + draft["draftId"], "PUT",
                {"expectedRevision": draft["revision"], "editingContent": content})
    check = api(f"/model/drafts/{draft['draftId']}/validate", "POST",
                {"expectedRevision": saved["revision"]})
    assert check["status"] == "VALID", check
    snapshot = api(f"/model/drafts/{draft['draftId']}/snapshots", "POST",
                   {"expectedRevision": saved["revision"],
                    "idempotencyKey": str(uuid4())})
    snapshot_id = snapshot["snapshotId"]

    def bind(type_id, table, key, mappings):
        body = {"snapshotId": snapshot_id, "objectTypeId": type_id,
                "sourceId": "local-pg-sandbox", "schema": "public",
                "table": table, "sourceKey": key, "expectedRevision": 0,
                "fieldMappings": [
                    {"propertyRef": property_id, "sourceColumn": column, "authority": "AUTHORITATIVE"}
                    for property_id, column in mappings
                ]}
        preview = api("/runtime/bindings/preview", "POST", body)
        body["expectedSchemaFingerprint"] = preview["source"]["schemaFingerprint"]
        binding = api("/runtime/bindings", "POST", body)
        assert binding["validationStatus"] == "VALID", binding
        return binding

    left = bind("factory-cell", "m8_factory_cells", "cell_code",
                [("cell-code", "cell_code"), ("cell-name", "cell_name"),
                 ("cell-site", "site"), ("cell-cost", "cost")])
    right = bind("quality-event", "m8_quality_events", "event_code",
                 [("event-code", "event_code"), ("cell-ref", "cell_code"),
                  ("event-status", "status"), ("event-amount", "amount")])
    assert left["identityDomainId"] != right["identityDomainId"]
    return snapshot_id, left, right


class RealM8BrowserGoldenStory(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.package = assigned_package()
        self.page = self.browser.new_page(viewport={"width": 1380, "height": 900},
                                          locale="zh-CN", extra_http_headers=headers())
        self.errors = []
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))

    def tearDown(self):
        if os.getenv("E2E_SHOTS"):
            output = Path(os.environ["E2E_SHOTS"])
            output.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(output / "m8_full_workflow.png"), full_page=True)
        self.assertEqual(self.errors, [])
        self.page.close()

    def test_real_native_to_m5_to_linkbinding_to_react_traverse_and_aggregate(self):
        snapshot, left, right = build_source(self.package)
        page = self.page
        page.goto(BASE + "/#/mvp/object-sets?packageId=" + quote(self.package)
                  + "&bindingId=" + quote(left["bindingId"]) + "&bindingRevision=1")
        page.get_by_role("heading", name="对象集合查询", exact=True).wait_for(timeout=18000)
        panel = page.get_by_role("region", name="关系与一跳遍历 M8.1")
        panel.get_by_role("button", name="展开关系工作台").click()
        panel.get_by_role("button", name="读取当前配置").click()
        panel.get_by_label("M8 LinkType").locator('option[value="cell-quality"]').wait_for(
            state="attached", timeout=20000)
        panel.get_by_label("M8 FK Column").select_option("cell_code")
        panel.get_by_role("button", name="执行 FULL 验证").click()
        panel.locator(".m8-validation").get_by_text("VALID_WITH_ORPHANS").wait_for(timeout=20000)
        quality = panel.locator(".m8-validation").inner_text()
        self.assertIn("匹配 2", quality)
        self.assertIn("SQL NULL 1", quality)
        self.assertIn("孤儿 1", quality)

        panel.get_by_role("button", name="保存 LinkBinding").click()
        panel.locator(".m8-current").get_by_text("revision 1").wait_for(timeout=20000)
        saved = api("/runtime/link-bindings?packageId=" + quote(self.package))
        self.assertEqual(len(saved), 1)
        detail = api("/runtime/link-bindings/" + saved[0]["id"]
                     + "?packageId=" + quote(self.package))
        self.assertEqual(detail["sourceBindingId"], left["bindingId"])
        self.assertEqual(detail["targetBindingId"], right["bindingId"])
        self.assertEqual(detail["validationQuality"]["matchedLinks"], 2)

        # Save a real signed M8 page cursor BEFORE API restart. The post-restart
        # CI step must return 410, not recover stale in-memory LinkFacts.
        cursor_query = {
            "queryVersion": "local-query-v2",
            "rootTypeRef": {"sourceRef": {"kind": "LOCAL_SNAPSHOT", "snapshotId": snapshot},
                            "packageId": self.package, "objectTypeId": "factory-cell"},
            "rootBindingRef": {"bindingId": left["bindingId"], "revision": left["revision"]},
            "rootSelector": {"kind": "OBJECT_REF",
                "objectRef": {"packageId": self.package, "objectTypeId": "factory-cell",
                    "identityDomainId": left["identityDomainId"],
                    "key": {"valueType": "STRING", "value": "CELL-01"}}},
            "steps": [{"kind": "TRAVERSE", "linkTypeRef": "cell-quality",
                       "linkBindingRef": {"bindingId": detail["linkBindingId"],
                                           "revision": detail["revision"]},
                       "direction": "OUT", "maxDepth": 1},
                      {"kind": "PAGE", "pageSize": 1}]
        }
        frozen = api("/runtime/object-sets/traverse", "POST", cursor_query)
        assert frozen["nextCursor"].startswith("v2.")
        # The real outside JWT has no assignment to this package. Neither a
        # fresh object query nor a copied signed cursor may reveal identities or
        # source evidence across workspaces.
        for endpoint, request_body in (
            ("/runtime/object-sets/traverse", cursor_query),
            ("/runtime/object-sets/traverse/pages", {"cursor": frozen["nextCursor"]}),
        ):
            outsider = Request(API + endpoint, data=json.dumps(request_body).encode("utf-8"),
                method="POST", headers={
                    "Authorization": "Bearer " + CONFIG["outsiderToken"],
                    "Content-Type": "application/json"})
            try:
                with urlopen(outsider, timeout=15):
                    raise AssertionError("Outside JWT unexpectedly received M8 protected object data")
            except HTTPError as denied:
                assert denied.code == 403, f"Expected JWT 403 on {endpoint}: {denied.code}"
                redacted = json.loads(denied.read())
                assert "objects" not in redacted and "links" not in redacted, redacted
                assert "sourceReadRefs" not in redacted and "CELL-01" not in str(redacted), redacted
        evidence = os.getenv("E2E_M8_CURSOR_EVIDENCE")
        if evidence:
            Path(evidence).write_text(json.dumps({
                "cursor": frozen["nextCursor"],
                "expiresAt": frozen["expiresAt"],
                "resultSnapshotRef": frozen["resultSnapshotRef"],
                "packageId": self.package
            }), encoding="utf-8")

        panel.get_by_label("M8 Root Object Key").fill("CELL-01")
        panel.get_by_role("button", name="执行一跳查询").click()
        result = panel.locator(".m8-result")
        result.get_by_text("COMPLETE", exact=True).first.wait_for(timeout=20000)
        self.assertIn("QE-11", result.inner_text())
        self.assertIn("QE-12", result.inner_text())
        self.assertIn("LinkFact", result.inner_text())

        panel.get_by_label("M8 Sort Field").select_option("event-code")
        panel.get_by_label("M8 Sort Direction").select_option("DESC")
        panel.get_by_label("M8 Project Field").select_option("event-amount")
        panel.get_by_role("button", name="执行一跳查询").click()
        result.locator("tbody tr").first.get_by_text("QE-12", exact=True).wait_for(timeout=20000)

        panel.get_by_label("M8 Result Mode").select_option("COUNT_DISTINCT")
        panel.get_by_role("button", name="执行一跳查询").click()
        result.locator(".m8-aggregate").get_by_text("COUNT_DISTINCT").wait_for(timeout=20000)
        self.assertIn("2", result.locator(".m8-aggregate").inner_text())
        self.assertEqual(result.locator(".m8-links").count(), 0)

        panel.get_by_label("M8 Result Mode").select_option("SUM")
        panel.get_by_label("M8 Sum Field").select_option("event-amount")
        panel.get_by_role("button", name="执行一跳查询").click()
        result.locator(".m8-aggregate").get_by_text("SUM", exact=False).wait_for(timeout=20000)
        self.assertIn("30", result.locator(".m8-aggregate").inner_text())
        self.assertIn("COMPLETE", result.locator(".m8-aggregate").inner_text())

        # A real orphan yields PARTIAL, never an incorrectly complete empty result.
        panel.get_by_label("M8 Result Mode").select_option("PAGE")
        panel.get_by_label("M8 Traverse Direction").select_option("IN")
        # Switching endpoints resets old target SORT/PROJECT choices. Otherwise
        # a valid orphan query is silently blocked by a stale output-side field.
        self.assertEqual(panel.get_by_label("M8 Sort Field").input_value(), "")
        self.assertEqual(panel.get_by_label("M8 Project Field").input_value(), "")
        panel.get_by_label("M8 Root Object Key").fill("QE-14")
        panel.get_by_role("button", name="执行一跳查询").click()
        result.get_by_text("PARTIAL", exact=True).first.wait_for(timeout=20000)
        self.assertIn("已知子集", result.inner_text())
        self.assertEqual(result.locator("tbody tr").count(), 0)


if __name__ == "__main__":
    unittest.main()
