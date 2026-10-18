"""M5 generic Data Binding golden story over real PG17, Spring Boot API, React and Chromium."""
import json
import os
import unittest
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, urlopen

from playwright.sync_api import sync_playwright
from b0_fixture import headers, assigned_package

BASE = "http://127.0.0.1:5173"
MODEL_API = "http://127.0.0.1:8080/api/model"
RUNTIME_API = "http://127.0.0.1:8080/api/runtime"


def api(base, path, method="GET", payload=None):
    request = Request(
        base + path,
        data=json.dumps(payload).encode("utf-8") if payload is not None else None,
        method=method,
        headers={"Content-Type": "application/json", **headers()},
    )
    with urlopen(request, timeout=15) as response:
        return json.load(response)


class DataBindingRealBrowserTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.page = self.browser.new_page(
            viewport={"width": 1380, "height": 920},
            locale="zh-CN",
            extra_http_headers=headers(),
        )
        self.errors = []
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))
        self.page.goto(BASE + "/#/model/packages")
        self.page.get_by_role("heading", name="模型包", exact=True).wait_for()
        self.package = assigned_package()

    def tearDown(self):
        if os.getenv("E2E_SHOTS"):
            dest = Path(os.getenv("E2E_SHOTS"))
            dest.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(dest / (self._testMethodName + ".png")), full_page=True)
        self.assertEqual(self.errors, [])
        self.page.close()

    def create_supplier_snapshot(self):
        page = self.page
        page.get_by_role("button", name="创建模型包").click()
        page.locator("#create-package-id").fill(self.package)
        page.get_by_role("button", name="创建并继续").click()
        page.locator("#obj-id").wait_for()
        draft_id = page.url.split("/")[-1]

        page.locator("#obj-id").fill("supplier-type")
        page.locator("#obj-api").fill("Supplier")
        page.locator("#obj-label").fill("供应商")
        page.locator("#obj-desc").fill("由用户自主定义并绑定真实受控来源的供应商对象")
        page.locator("#add-property").click()
        properties = [
            ("supplier-code", "vendorCode", "供应商编号", "STRING", True),
            ("supplier-name", "name", "供应商名称", "STRING", True),
            ("supplier-rating", "rating", "供应商评级", "DECIMAL", False),
            ("supplier-active", "active", "是否有效", "BOOLEAN", True),
            ("supplier-onboarded", "onboardedAt", "入驻时间", "DATETIME", False),
            ("supplier-legacy", "legacyCode", "旧系统编码", "STRING", False),
            ("supplier-unmapped-rating", "unmappedRating", "未绑定数值", "DECIMAL", False),
        ]
        for index, (property_id, api_name, label, value_type, required) in enumerate(properties):
            if index > 0:
                page.locator("#add-property").click()
            page.locator(f"#prop-{index}-id").fill(property_id)
            page.locator(f"#prop-{index}-apiName").fill(api_name)
            page.locator(f"#prop-{index}-label").fill(label)
            page.locator(f"#prop-{index}-valueType").select_option(value_type)
            required_control = page.locator(f"#prop-{index}-required")
            if required and not required_control.is_checked():
                required_control.check()
            elif not required and required_control.is_checked():
                required_control.uncheck()
            # M7 deliberately exposes filters only for properties explicitly
            # marked searchable in the immutable ObjectType contract.
            if property_id in ("supplier-name", "supplier-legacy"):
                searchable_control = page.locator(f"#prop-{index}-searchable")
                if not searchable_control.is_checked():
                    searchable_control.check()
        page.locator("#obj-pk").select_option("supplier-code")
        page.get_by_role("button", name="保存编辑进度").click()
        page.get_by_text("已写入 PostgreSQL", exact=False).wait_for(timeout=15000)
        page.get_by_role("button", name="检查模型完整性").click()
        page.get_by_text("完整结构与本阶段语义校验通过", exact=False).wait_for(timeout=15000)

        draft = api(MODEL_API, "/drafts/" + draft_id)
        self.assertEqual(draft["validation"]["status"], "VALID")
        self.assertEqual(draft["validation"]["validatedRevision"], draft["revision"])

        page.locator("#snapshot-freeze").wait_for()
        page.locator("#snapshot-freeze").click()
        page.get_by_role("dialog", name="确认冻结当前草稿修订").wait_for()
        page.locator("#snapshot-confirm").click()
        page.locator("#snapshot-sha").wait_for(timeout=15000)
        snapshot_id = page.url.split("/")[-1]
        self.assertRegex(snapshot_id, r"^[a-f0-9-]{36}$")
        snapshot_type = api(MODEL_API, f"/type-sources/snapshots/{snapshot_id}/types/supplier-type")
        self.assertEqual(snapshot_type["sourceRef"], {"kind": "LOCAL_SNAPSHOT", "snapshotId": snapshot_id})
        self.assertEqual(snapshot_type["packageId"], self.package)
        return snapshot_id

    def test_custom_object_type_maps_previews_validates_saves_and_reopens(self):
        page = self.page
        snapshot_id = self.create_supplier_snapshot()

        # Validate the actual M4 -> M5 entry, not a direct-only route into the editor.
        page.get_by_role("button", name="查看运行时类型").click()
        page.get_by_role("heading", name="运行时类型目录").wait_for()
        page.locator(".runtime-type-item").get_by_text("供应商", exact=True).click()
        page.get_by_role("button", name="创建绑定").click()
        page.get_by_role("heading", name="数据绑定", exact=True).wait_for()
        page.get_by_role("heading", name="创建通用绑定", exact=True).wait_for(timeout=20000)

        mappings = {
            "vendorCode": "vendor_code",
            "name": "name",
            "rating": "rating",
            "active": "active",
            "onboardedAt": "onboarded_at",
        }
        for property_name, source_column in mappings.items():
            page.locator(f'select[aria-label="来源列 {property_name}"]').select_option(source_column)

        page.get_by_role("button", name="预览映射").click()
        page.locator(".binding-preview").wait_for(timeout=15000)
        self.assertIn("Northwind Components", page.locator(".binding-preview").inner_text())
        self.assertIn("供应商评级", page.locator(".binding-editor").inner_text())

        page.get_by_role("button", name="运行有界校验").click()
        page.locator(".binding-validation.pass").wait_for(timeout=15000)
        self.assertIn("校验通过", page.locator(".binding-validation.pass").inner_text())

        page.get_by_role("button", name="保存 Binding revision").click()
        page.get_by_role("heading", name="绑定详情", exact=True).wait_for(timeout=15000)
        page.get_by_text("绑定 revision 1 已持久化", exact=False).wait_for(timeout=15000)

        rows = api(RUNTIME_API, "/bindings?packageId=" + quote(self.package, safe=""))
        self.assertEqual(len(rows), 1)
        binding_id = rows[0]["bindingId"]
        self.assertEqual(rows[0]["currentRevision"], 1)
        self.assertEqual(rows[0]["sourceTable"], "m5_supplier_source")

        saved = api(RUNTIME_API, f"/bindings/{binding_id}?revision=1")
        self.assertEqual(saved["validationStatus"], "VALID")
        self.assertEqual(saved["checkedRows"], 5)
        self.assertEqual(saved["snapshotId"], snapshot_id)
        self.assertEqual(saved["sourceKey"], "vendor_code")
        self.assertEqual(
            {item["sourceColumn"] for item in saved["fieldMappings"]},
            set(mappings.values()),
        )
        self.assertRegex(saved["digest"], r"^[0-9a-f]{64}$")
        revision1_identity = saved["identityDomainId"]
        revision1_digest = saved["digest"]

        # Reopen from the persisted directory, then explicitly change identity key semantics.
        page.get_by_role("button", name="返回目录").click()
        page.get_by_role("button", name="查看 / 历史").click()
        page.get_by_role("heading", name="绑定详情", exact=True).wait_for()
        self.assertIn("revision 1", page.locator(".model-panel").inner_text())
        self.assertEqual(api(RUNTIME_API, f"/bindings/{binding_id}?revision=1")["digest"], revision1_digest)

        page.get_by_role("button", name="创建新 revision").click()
        page.get_by_label("来源身份键").select_option("alternate_code")
        page.get_by_role("button", name="预览映射").click()
        page.locator(".binding-preview").wait_for(timeout=15000)
        self.assertIn("ALT-SUP-1001", page.locator(".binding-preview").inner_text())
        page.get_by_role("button", name="运行有界校验").click()
        page.locator(".binding-validation.pass").wait_for(timeout=15000)
        page.get_by_role("button", name="保存 Binding revision").click()
        page.get_by_role("heading", name="绑定详情", exact=True).wait_for(timeout=15000)
        page.get_by_text("旧 ObjectRef 不会自动重定位", exact=False).wait_for(timeout=15000)

        current = api(RUNTIME_API, f"/bindings/{binding_id}?revision=2")
        self.assertEqual(current["revision"], 2)
        self.assertEqual(current["sourceKey"], "alternate_code")
        self.assertNotEqual(current["identityDomainId"], revision1_identity)
        self.assertEqual(api(RUNTIME_API, f"/bindings/{binding_id}?revision=1")["digest"], revision1_digest)
        preserved = api(RUNTIME_API, f"/bindings/{binding_id}?revision=1")
        self.assertEqual(preserved["identityDomainId"], revision1_identity)

        evidence_path = os.getenv("E2E_M5_BINDING_EVIDENCE")
        if evidence_path:
            Path(evidence_path).write_text(json.dumps({
                "packageId": self.package,
                "bindingId": binding_id,
                "snapshotId": snapshot_id,
                "revision1": 1,
                "digest1": revision1_digest,
                "identityDomainId1": revision1_identity,
                "revision2": 2,
                "digest2": current["digest"],
                "identityDomainId2": current["identityDomainId"],
            }, indent=2), encoding="utf-8")

        # Reopen both the current head and the immutable historical revision.
        page.get_by_role("button", name="返回目录").click()
        page.get_by_role("button", name="查看 / 历史").click()
        page.get_by_role("heading", name="绑定详情", exact=True).wait_for()
        self.assertIn("revision 2", page.locator(".model-panel").inner_text())
        page.get_by_role("button", name="重开历史版").click()
        # The static "绑定详情" heading already exists before the async fetch;
        # wait for the selected historical revision itself to become current.
        page.get_by_text("supplier-type · revision 1", exact=False).wait_for(timeout=15000)
        historical_panel = page.locator(".model-panel").inner_text()
        self.assertIn("revision 1", historical_panel)
        self.assertIn(revision1_identity, historical_panel)

        # M6 vertical story: use the persisted current Binding revision to read a real supplier.
        page.get_by_role("button", name="返回目录").click()
        page.get_by_role("button", name="查看 / 历史").click()
        page.get_by_role("heading", name="绑定详情", exact=True).wait_for()
        page.get_by_text("supplier-type · revision 2", exact=True).wait_for(timeout=15000)
        page.get_by_role("button", name="读取对象").click()
        page.get_by_role("heading", name="对象读取", exact=True).wait_for(timeout=15000)
        page.locator("#object-runtime-key").fill("ALT-SUP-1001")
        page.locator("#read-object").click()
        page.locator(".object-runtime-result .object-runtime-property-table").wait_for(timeout=15000)
        result_text = page.locator(".object-runtime-result").inner_text()
        self.assertIn("Northwind Components", result_text)
        self.assertIn("PRESENT", result_text)
        self.assertIn("MISSING", result_text)
        self.assertIn("REPEATABLE_READ", page.locator(".object-runtime-evidence").inner_text())
        self.assertIn("UNKNOWN", page.locator(".object-runtime-evidence").inner_text())

        # A source miss is a typed 404, not a successful empty object or stale prior result.
        page.locator("#object-runtime-key").fill("ALT-SUP-9999")
        page.locator("#read-object").click()
        page.locator(".object-runtime-error").wait_for(timeout=15000)
        self.assertIn("OBJECT_NOT_FOUND", page.locator(".object-runtime-error").inner_text())
        self.assertNotIn("Northwind Components", page.locator(".object-runtime-result").inner_text())

        # M7 vertical story: the same saved custom Supplier Binding drives a real ObjectSet query.
        page.goto(BASE + "/#/mvp/object-sets?packageId=" + quote(self.package)
                  + "&bindingId=" + quote(binding_id) + "&bindingRevision=2")
        page.get_by_role("heading", name="对象集合查询", exact=True).wait_for(timeout=15000)
        filter_property = page.locator(".object-set-filter-row select").nth(0)
        # The route heading renders before async binding/type resolution; wait for
        # the resolved selector contract before inspecting its options.
        filter_property.locator('option[value="supplier-name"]').wait_for(state="attached", timeout=15000)
        page.locator('.object-set-sort-row select option[value="supplier-code"]').wait_for(state="attached", timeout=15000)
        filter_options = filter_property.locator("option").evaluate_all("(options) => options.map(option => option.value)")
        self.assertIn("supplier-name", filter_options)
        self.assertNotIn("supplier-legacy", filter_options)
        sort_options = page.locator(".object-set-sort-row select").nth(0).locator("option").evaluate_all("(options) => options.map(option => option.value)")
        self.assertNotIn("supplier-legacy", sort_options)
        filter_property.select_option("supplier-name")
        page.locator(".object-set-filter-row select").nth(1).select_option("PREFIX")
        page.locator(".object-set-filter-row input").fill("North")
        page.get_by_role("button", name="执行查询", exact=True).click()
        page.locator(".object-set-result-summary").wait_for(timeout=15000)
        query_text = page.locator(".object-set-result-panel").inner_text()
        self.assertIn("Northwind Components", query_text)
        self.assertIn("匹配对象总数", query_text)
        self.assertIn("当前页 / 总页数", query_text)
        self.assertIn("UNKNOWN", page.locator(".object-set-evidence").inner_text())
        self.assertIn("local-result:", page.locator(".object-set-evidence").inner_text())
        self.assertNotIn("Changed after first page", query_text)
        query_summary = page.locator(".object-set-query-summary").inner_text()
        self.assertIn("筛选", query_summary)
        self.assertIn("Binding revision", query_summary)
        self.assertIn("UNKNOWN", query_summary)

        # Simulate a page revalidation failure after a successful first page. The UI
        # must hide all result rows while retaining independent snapshot/execution evidence.
        failure_evidence = {}
        def add_test_cursor(route):
            response = route.fetch()
            payload = response.json()
            if response.ok and payload.get("status") == "SUCCESS" and payload.get("objects") is not None:
                payload["nextCursor"] = "m7-ui-test-cursor-not-a-real-server-token"
                payload["totalCount"] = max(payload.get("totalCount", 0), 2)
                failure_evidence["snapshot"] = payload["resultSnapshotRef"]
            route.fulfill(response=response, json=payload)

        def fail_next_page(route):
            route.fulfill(status=503, content_type="application/json", body=json.dumps({
                "status": "FAILED", "executionId": "m7-ui-failure-simulation",
                "code": "SOURCE_UNAVAILABLE", "message": "Simulated page revalidation failure.",
                "fieldPath": "source",
                "quality": {"accuracy": "UNKNOWN", "completeness": "UNKNOWN",
                            "freshness": "UNKNOWN", "answerScope": "REQUESTED_SCOPE"},
            }))

        page.route("**/api/runtime/object-sets/query", add_test_cursor)
        page.route("**/api/runtime/object-sets/pages", fail_next_page)
        page.get_by_role("button", name="执行查询", exact=True).click()
        # The first real query had no next cursor; wait for the injected cursor to render
        # rather than racing an already-existing result summary from the previous run.
        page.get_by_role("button", name="下一页 →", exact=True).wait_for(timeout=15000)
        self.assertIn("当前页 / 总页数", page.locator(".object-set-result-summary").inner_text())
        page.get_by_role("button", name="下一页 →", exact=True).click()
        page.locator(".object-set-failure-evidence").wait_for(timeout=15000)
        failure_text = page.locator(".object-set-failure-evidence").inner_text()
        self.assertIn(failure_evidence["snapshot"], failure_text)
        self.assertIn("SOURCE_UNAVAILABLE", failure_text)
        self.assertNotIn("Northwind Components", page.locator(".object-set-result-panel").inner_text())
        page.unroute("**/api/runtime/object-sets/query", add_test_cursor)
        page.unroute("**/api/runtime/object-sets/pages", fail_next_page)

        # The aggregate mode is mutually exclusive with sorting, projection and paging.
        page.get_by_role("button", name="聚合统计", exact=True).click()
        page.locator(".object-set-aggregate-choice select").nth(0).select_option("SUM")
        aggregate_properties = page.locator(".object-set-aggregate-choice select").nth(1).locator("option").evaluate_all("(options) => options.map(option => option.value)")
        self.assertIn("supplier-rating", aggregate_properties)
        self.assertNotIn("supplier-unmapped-rating", aggregate_properties)
        page.locator(".object-set-aggregate-choice select").nth(1).select_option("supplier-rating")
        page.get_by_role("button", name="执行查询", exact=True).click()
        page.locator(".object-set-aggregate-result").wait_for(timeout=15000)
        aggregate_text = page.locator(".object-set-aggregate-result").inner_text()
        self.assertIn("SUM", aggregate_text)
        self.assertIn("匹配对象", aggregate_text)
        self.assertIn("UNKNOWN", page.locator(".object-set-evidence").inner_text())
        # Persist one short-lived signed cursor so the workflow can verify both B0
        # authorization revocation and real backend-restart invalidation.
        evidence_path = os.getenv("E2E_M7_CURSOR_EVIDENCE")
        if evidence_path:
            binding = api(RUNTIME_API, f"/bindings/{binding_id}?revision=2")
            cursor_query = {
                "queryVersion": "local-query-v1",
                "rootTypeRef": {
                    "sourceRef": {"kind": "LOCAL_SNAPSHOT", "snapshotId": binding["snapshotId"]},
                    "packageId": binding["packageId"],
                    "objectTypeId": binding["objectTypeId"],
                },
                "bindingRef": {"bindingId": binding_id, "revision": 2},
                "steps": [
                    {"kind": "SORT", "fields": [{"propertyRef": "supplier-code", "direction": "ASC"}]},
                    {"kind": "PROJECT", "propertyRefs": ["supplier-name", "supplier-rating"]},
                    {"kind": "PAGE", "pageSize": 2},
                ],
            }
            cursor_first = api(RUNTIME_API, "/object-sets/query", "POST", cursor_query)
            self.assertEqual(cursor_first["status"], "SUCCESS")
            self.assertTrue(cursor_first.get("nextCursor"))
            Path(evidence_path).write_text(json.dumps({
                "packageId": self.package,
                "bindingId": binding_id,
                "snapshotId": binding["snapshotId"],
                "bindingRevision": 2,
                "resultSnapshotRef": cursor_first["resultSnapshotRef"],
                "queryDigest": cursor_first["queryDigest"],
                "query": cursor_query,
                "expiresAt": cursor_first["expiresAt"],
                "cursor": cursor_first["nextCursor"],
            }, indent=2), encoding="utf-8")


