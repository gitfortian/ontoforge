"""M8.2 production React + Spring HTTP + JWT + PostgreSQL 17 Bridge golden story.

The browser does NOT mock HTTP/identity/model/facts. It configures and saves a
real frozen MANY_TO_MANY Bridge revision and executes actual ObjectSet v2.
Only one intentional one-shot aborted POST simulates UNKNOWN network recovery;
the retry uses the REAL HTTP server with the original idempotency key.
"""
import json
import os
import subprocess
import unittest
from pathlib import Path
from urllib.parse import quote

from playwright.sync_api import expect, sync_playwright
from b0_fixture import assigned_package, headers
from test_real_m8_full_workflow import BASE, build_source, api


class RealBridgeReactGoldenStory(unittest.TestCase):
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
        self.page = self.browser.new_page(
            viewport={"width": 1366, "height": 900},
            locale="zh-CN", extra_http_headers=headers())
        self.js_errors = []
        self.page.on("pageerror", lambda error: self.js_errors.append(str(error)))

    def tearDown(self):
        if os.getenv("E2E_SHOTS"):
            directory = Path(os.environ["E2E_SHOTS"])
            directory.mkdir(parents=True, exist_ok=True)
            try:
                self.page.screenshot(
                    path=str(directory / "m8_bridge_react.png"),
                    full_page=True, timeout=15000)
            except Exception:
                # Capture failure is visible, not silently missing evidence.
                self.page.screenshot(
                    path=str(directory / "m8_bridge_react_viewport.png"),
                    full_page=False, timeout=15000)
        self.assertEqual(self.js_errors, [])
        self.page.close()

    def test_bridge_full_save_unknown_recovery_out_in_sum_and_reopen_375px(self):
        snapshot, source, _ = build_source(self.package)
        page = self.page
        page.goto(BASE + "/#/mvp/object-sets?packageId=" + quote(self.package)
                  + "&bindingId=" + quote(source["bindingId"]) + "&bindingRevision=1")
        page.get_by_role("heading", name="对象集合查询", exact=True).wait_for(timeout=20000)
        panel = page.get_by_role("region", name="关系与一跳遍历 M8")
        panel.get_by_role("button", name="展开关系工作台").click()
        panel.get_by_role("button", name="读取当前配置").click()
        panel.get_by_label("M8 LinkType").locator(
            'option[value="cell-quality-bridge"]').wait_for(
                state="attached", timeout=20000)
        panel.get_by_label("M8 LinkType").select_option("cell-quality-bridge")
        panel.get_by_label("M8 Bridge Table").select_option("m8_cell_event_bridge")
        panel.get_by_label("M8 Bridge SOURCE FK").select_option("cell_code")
        panel.get_by_label("M8 Bridge TARGET FK").select_option("event_code")
        self.assertTrue(panel.get_by_role("button", name="执行 FULL 验证").is_enabled())
        panel.get_by_role("button", name="执行 FULL 验证").click()
        quality = panel.locator(".m8-bridge-quality")
        quality.get_by_text("10", exact=True).first.wait_for(timeout=25000)
        values = quality.inner_text()
        for text in ("桥表物理行", "匹配物理行", "唯一 Pair",
                     "重复物理 Pair", "SOURCE 孤儿", "TARGET 孤儿",
                     "双孤儿", "双 NULL", "仅 SOURCE NULL", "仅 TARGET NULL"):
            self.assertIn(text, values)
        self.assertIn("VALID_WITH_ORPHANS", panel.locator(".m8-validation").inner_text())

        # The first POST fails at transport, before reaching the source DB.
        # UI must retain same operation key and permit an explicit retry.
        state = {"aborted": False, "request": None}
        def fail_once(route):
            if route.request.method == "POST" and not state["aborted"]:
                state["aborted"] = True
                route.abort("failed")
            else:
                if route.request.method == "POST":
                    state["request"] = route.request.post_data_json
                route.continue_()
        page.route("**/api/runtime/link-bindings", fail_once)
        panel.get_by_role("button", name="保存 LinkBinding").click()
        expect(panel.get_by_role("alert").first).to_contain_text(
            "NETWORK_UNAVAILABLE", timeout=20000)
        recovery = panel.locator(".m8-operation")
        operation_key = recovery.locator("code").inner_text()
        self.assertEqual(len(operation_key), 36)
        self.assertTrue(recovery.get_by_role("button", name="使用原操作键重试保存").is_enabled())
        recovery.get_by_role("button", name="使用原操作键重试保存").click()
        panel.locator(".m8-current").get_by_text("revision 1").wait_for(timeout=20000)
        # H04: one FULL qualification grants one successful immutable save.
        # A second click must not silently create revision 2; unknown-network
        # retry above used the original UUID before success.
        expect(panel.get_by_role("button", name="保存新 revision")).to_be_disabled()
        self.assertEqual(state["aborted"], True)
        self.assertEqual(recovery.count(), 0)
        saved = api("/runtime/link-bindings?packageId=" + quote(self.package))
        heads = [head for head in saved if head["linkTypeId"] == "cell-quality-bridge"]
        self.assertEqual(len(heads), 1)
        detail = api("/runtime/link-bindings/" + heads[0]["id"]
                     + "?packageId=" + quote(self.package))
        self.assertEqual(detail["mappingMode"], "BRIDGE_TABLE")
        self.assertEqual(detail["bridgeSourceRef"]["table"], "m8_cell_event_bridge")
        self.assertEqual(detail["revision"], 1)
        self.assertEqual(detail["validationQuality"]["duplicatePairRowCount"], 1)
        self.assertIsNotNone(state["request"], "Actual saved Bridge v2 body must be captured")
        self.assertEqual(state["request"]["idempotencyKey"], operation_key)
        # Feed the exact REAL browser/API body to a separate, post-browser
        # two-JVM concurrency test. The file contains no JWT credentials.
        if os.getenv("E2E_M8_BRIDGE_RECEIPT_EVIDENCE"):
            Path(os.environ["E2E_M8_BRIDGE_RECEIPT_EVIDENCE"]).write_text(
                json.dumps({"id": heads[0]["id"], "packageId": self.package,
                            "request": state["request"]}, indent=2), encoding="utf-8")

        panel.get_by_label("M8 Root Object Key").fill("CELL-01")
        panel.get_by_role("button", name="执行一跳查询").click()
        result = panel.locator(".m8-result")
        result.get_by_text("PARTIAL", exact=True).first.wait_for(timeout=20000)
        self.assertIn("QE-11", result.inner_text())
        self.assertIn("QE-12", result.inner_text())
        self.assertIn("BRIDGE", result.inner_text())
        self.assertIn("三方 SourceReadRefs：3", result.inner_text())
        self.assertIn("重复 Pair 行：1", result.inner_text())
        self.assertEqual(result.locator(".m8-links tbody tr").count(), 2)
        self.assertEqual(result.locator(".m8-source-detail").count(), 3)

        panel.get_by_label("M8 Result Mode").select_option("COUNT_DISTINCT")
        panel.get_by_role("button", name="执行一跳查询").click()
        result.locator(".m8-aggregate").get_by_text(
            "COUNT_DISTINCT").wait_for(timeout=20000)
        self.assertIn("2", result.locator(".m8-aggregate").inner_text())
        panel.get_by_label("M8 Result Mode").select_option("SUM")
        panel.get_by_label("M8 Sum Field").select_option("event-amount")
        panel.get_by_role("button", name="执行一跳查询").click()
        result.locator(".m8-aggregate").get_by_text("SUM").wait_for(timeout=20000)
        self.assertIn("30", result.locator(".m8-aggregate").inner_text())
        self.assertIn("PARTIAL", result.locator(".m8-aggregate").inner_text())
        self.assertIn("已知子集小计（非全量）", result.locator(".m8-aggregate").inner_text())

        panel.get_by_label("M8 Result Mode").select_option("PAGE")
        panel.get_by_label("M8 Traverse Direction").select_option("IN")
        panel.get_by_label("M8 Root Object Key").fill("QE-11")
        panel.get_by_role("button", name="执行一跳查询").click()
        result.get_by_text("PARTIAL", exact=True).first.wait_for(timeout=20000)
        self.assertIn("CELL-01", result.inner_text())
        self.assertEqual(result.locator(".m8-links tbody tr").count(), 1)

        # Reload the saved revision, not the local form state.
        panel.get_by_role("button", name="读取当前配置").click()
        panel.get_by_label("M8 LinkType").select_option("cell-quality-bridge")
        panel.get_by_role("button", name="重开已保存").click()
        expect(panel.get_by_label("M8 Bridge Table")).to_have_value(
            "m8_cell_event_bridge", timeout=20000)
        self.assertEqual(panel.get_by_label("M8 Bridge SOURCE FK").input_value(), "cell_code")
        self.assertEqual(panel.get_by_label("M8 Bridge TARGET FK").input_value(), "event_code")
        expect(panel.get_by_role("button", name="保存新 revision")).to_be_disabled()

        page.set_viewport_size({"width": 375, "height": 812})
        expect(panel.get_by_role("button", name="收起工作台")).to_be_visible()
        expect(panel.get_by_label("M8 Bridge Table")).to_be_visible()
        expect(panel.get_by_label("M8 Bridge SOURCE FK")).to_be_visible()
        expect(panel.get_by_label("M8 Bridge TARGET FK")).to_be_visible()
        self.assertEqual(panel.locator(".m8-bridge-quality").count(), 1)
        self.assertLessEqual(page.evaluate(
            "() => document.documentElement.scrollWidth - document.documentElement.clientWidth"), 24)

        # H12 production (not UX prototype): verify each supported width
        # and keyboard-focusable workflow controls against the actual React DOM.
        for width in (768, 1024, 1440):
            page.set_viewport_size({"width": width, "height": 900})
            expect(panel.get_by_label("M8 Bridge Table")).to_be_visible()
            expect(panel.get_by_role("button", name="执行一跳查询")).to_be_visible()
            self.assertLessEqual(page.evaluate(
                "() => document.documentElement.scrollWidth "
                "- document.documentElement.clientWidth"), 24)
            if os.getenv("E2E_SHOTS"):
                folder = Path(os.environ["E2E_SHOTS"])
                folder.mkdir(parents=True, exist_ok=True)
                page.screenshot(path=str(folder / ("m8_bridge_react_" + str(width) + ".png")),
                                full_page=True)

        # H10 production authorization loss: genuinely revoke the dedicated
        # PostgreSQL source role AFTER objects and SourceReadRefs were rendered.
        # The second real HTTP query must fail closed; no old ObjectRefs or
        # LinkFacts may remain in the React DOM. Restore the permission in
        # finally so all other JWT/PG17 tests are independent.
        pg_containers = subprocess.check_output(
            ["docker", "ps", "--format", "{{.ID}} {{.Image}}"], text=True).splitlines()
        pg_id = next((line.split()[0] for line in pg_containers
                      if len(line.split()) > 1
                      and line.split()[1].startswith("postgres:17")), None)
        self.assertIsNotNone(pg_id, "Real PostgreSQL 17 fixture must exist")
        def source_sql(sql):
            subprocess.run(
                ["docker", "exec", "-i", pg_id, "psql", "-X", "-v", "ON_ERROR_STOP=1",
                 "-U", "ontology", "-d", "ontoforge_test_e2e"],
                input=sql, text=True, capture_output=True, check=True)

        panel.get_by_label("M8 Traverse Direction").select_option("OUT")
        panel.get_by_label("M8 Root Object Key").fill("CELL-01")
        panel.get_by_role("button", name="执行一跳查询").click()
        result = panel.locator(".m8-result")
        expect(result).to_contain_text("QE-11", timeout=20000)
        expect(result).to_contain_text("BRIDGE")
        source_sql("REVOKE SELECT ON public.m8_cell_event_bridge "
                   "FROM ontoforge_binding_reader;")
        try:
            panel.get_by_role("button", name="执行一跳查询").click()
            expect(panel.locator(".object-set-error")).to_contain_text(
                "LINK_SOURCE_UNAVAILABLE", timeout=20000)
            expect(panel.locator(".m8-result")).to_have_count(0)
            expect(panel.locator(".m8-validation")).to_have_count(0)
            expect(panel.locator(".m8-current")).to_have_count(0)
            expect(panel.locator(".m8-links tbody tr")).to_have_count(0)
            expect(panel.locator(".m8-source-detail")).to_have_count(0)
            self.assertNotIn("QE-11", panel.inner_text())
            self.assertNotIn("QE-12", panel.inner_text())
            if os.getenv("E2E_SHOTS"):
                folder = Path(os.environ["E2E_SHOTS"])
                folder.mkdir(parents=True, exist_ok=True)
                page.screenshot(path=str(folder / "m8_bridge_react_h10_revoked.png"),
                                full_page=True)
        finally:
            source_sql("GRANT SELECT ON public.m8_cell_event_bridge "
                       "TO ontoforge_binding_reader;")

        # Permission restoration never magically resurrects an old result.
        # A deliberate fresh action produces new trusted current evidence.
        expect(panel.locator(".m8-result")).to_have_count(0)
        expect(panel.get_by_role("button", name="执行一跳查询")).to_be_disabled()
        panel.get_by_role("button", name="重开已保存").click()
        expect(panel.locator(".m8-current")).to_contain_text("revision 1", timeout=20000)
        expect(panel.get_by_role("button", name="保存新 revision")).to_be_disabled()
        panel.get_by_role("button", name="执行一跳查询").click()
        expect(panel.locator(".m8-result")).to_contain_text("QE-11", timeout=20000)
        self.assertEqual(panel.locator(".m8-source-detail").count(), 3)


if __name__ == "__main__":
    unittest.main()
