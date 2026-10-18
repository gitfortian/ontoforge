"""Real Chromium checks for the M7 ObjectSet UX-Low static prototype.

These tests verify prototype interaction only. They do not call a real ObjectSet API
and are not evidence that the M7 query runtime has been implemented.
"""
from __future__ import annotations

import os
import unittest
from pathlib import Path

from playwright.sync_api import sync_playwright

PAGE = Path(__file__).resolve().parents[1] / "m7-objectset-lowfi.html"


class M7ObjectSetLowFiBrowser(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.page = self.browser.new_page(viewport={"width": 1440, "height": 1000}, locale="zh-CN")
        self.console_errors: list[str] = []
        self.page.on("pageerror", lambda error: self.console_errors.append(str(error)))
        self.page.goto(PAGE.as_uri())
        self.page.get_by_role("heading", name="对象集合查询", exact=True).wait_for()

    def tearDown(self):
        if os.environ.get("UX_CAPTURE_SCREENSHOTS"):
            target = Path(os.environ.get("UX_SCREENSHOT_DIR", "/tmp/ontoforge-m7-lowfi-shots"))
            target.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(target / (self._testMethodName + ".png")), full_page=True)
        self.assertEqual(self.console_errors, [], "prototype should not emit uncaught browser errors")
        self.page.close()

    def test_query_contract_shows_typed_filter_stages_and_evidence(self):
        self.assertIn("静态交互原型", self.page.locator(".notice").inner_text())
        ast = self.page.locator("#query-ast").inner_text()
        self.assertIn("Filter → Sort → Project → Page", ast)
        self.assertIn("rating GT 4.0", ast)
        self.assertIn("active EQ true", ast)
        self.assertIn("snapshot-local-021", ast)
        self.assertIn("result-local-001", ast)

    def test_operator_incompatible_with_property_type_fails_closed(self):
        self.page.locator("#filter-property-0").select_option("name")
        self.page.locator("#filter-op-0").select_option("GT")
        self.page.get_by_role("button", name="执行查询").click()
        self.assertIn("422 INVALID_QUERY_AST", self.page.locator("#error-result").inner_text())
        self.assertTrue(self.page.locator("#result-success").evaluate("(e) => e.classList.contains('hidden')"))

    def test_filter_count_is_bounded_to_eight(self):
        add = self.page.get_by_role("button", name="＋ 添加条件")
        add.click()
        add.click()
        add.click()
        add.click()
        add.click()
        add.click()
        self.assertEqual(self.page.locator("[data-filter-row]").count(), 8)
        add.click()
        self.assertEqual(self.page.locator("[data-filter-row]").count(), 8)

    def test_page_size_drives_stable_snapshot_pagination(self):
        self.page.locator("#page-size").select_option("3")
        self.page.get_by_role("button", name="执行查询").click()
        self.assertTrue(self.page.locator("#result-success").is_visible())
        self.assertEqual(self.page.locator("#metric-count").inner_text(), "10")
        self.assertEqual(self.page.locator("#metric-page").inner_text(), "1 / 4")
        self.assertEqual(self.page.locator("#result-rows tr").count(), 3)
        self.assertIn("同一 resultSnapshotRef", self.page.locator("#page-note").inner_text())

        self.page.get_by_role("button", name="下一页").click()
        self.assertEqual(self.page.locator("#metric-page").inner_text(), "2 / 4")
        self.assertEqual(self.page.locator("#result-rows tr").count(), 3)
        self.page.get_by_role("button", name="上一页").click()
        self.assertEqual(self.page.locator("#metric-page").inner_text(), "1 / 4")

        self.page.locator("#page-size").select_option("5")
        self.assertTrue(self.page.locator("#result-success").evaluate("(e) => e.classList.contains('hidden')"))
        self.assertIn("查询条件已变更", self.page.locator("#result-empty").inner_text())
        self.page.get_by_role("button", name="执行查询").click()
        self.assertEqual(self.page.locator("#metric-page").inner_text(), "1 / 2")
        self.assertEqual(self.page.locator("#result-rows tr").count(), 5)

    def test_aggregate_mode_returns_exact_mock_metric_without_page_controls(self):
        self.page.get_by_role("button", name="聚合统计").click()
        self.assertTrue(self.page.locator("#aggregate-options").is_visible())
        self.assertFalse(self.page.locator("#page-options").is_visible())
        self.page.locator("#aggregate-op").select_option("COUNT_DISTINCT")
        self.page.get_by_role("button", name="执行查询").click()
        self.assertTrue(self.page.locator("#aggregate-result").is_visible())
        self.assertEqual(self.page.locator("#aggregate-value").inner_text(), "10")
        self.assertFalse(self.page.locator("#objects-table").is_visible())
        self.page.locator("#aggregate-op").select_option("SUM")
        self.page.get_by_role("button", name="执行查询").click()
        self.assertEqual(self.page.locator("#aggregate-value").inner_text(), "923400.00")
        self.assertIn("SUM(annualSpend)", self.page.locator("#aggregate-label").inner_text())

    def test_failure_scenarios_never_show_partial_result(self):
        scenarios = [
            ("invalid", "422 INVALID_QUERY_AST"),
            ("budget", "429 QUERY_BUDGET_EXCEEDED"),
            ("unavailable", "503 SOURCE_UNAVAILABLE"),
            ("drift", "409 BINDING_SCHEMA_DRIFT"),
            ("expired", "410 RESULT_EXPIRED"),
        ]
        for scenario, code in scenarios:
            with self.subTest(scenario=scenario):
                self.page.locator("#scenario").select_option(scenario)
                self.page.get_by_role("button", name="执行查询").click()
                error = self.page.locator("#error-result")
                self.assertTrue(error.is_visible())
                self.assertIn(code, error.inner_text())
                self.assertTrue(self.page.locator("#result-success").evaluate("(e) => e.classList.contains('hidden')"))
                self.assertNotIn("Northwind Components", self.page.locator("body").inner_text())

    def test_source_revocation_between_pages_blocks_next_page(self):
        self.page.locator("#scenario").select_option("page-revoked")
        self.page.get_by_role("button", name="执行查询").click()
        self.assertTrue(self.page.locator("#result-success").is_visible())
        self.page.get_by_role("button", name="下一页").click()
        self.assertIn("503 SOURCE_UNAVAILABLE", self.page.locator("#error-result").inner_text())
        self.assertTrue(self.page.locator("#result-success").evaluate("(e) => e.classList.contains('hidden')"))
        self.assertNotIn("Northwind Components", self.page.locator("body").inner_text())

    def test_binding_revision_switch_updates_version_lock_and_reset_clears_result(self):
        self.page.locator("#binding-revision").select_option("2")
        self.assertIn("revision 2", self.page.locator("#binding-context").inner_text())
        self.assertIn("revision 2", self.page.locator("#query-ast").inner_text())
        self.page.get_by_role("button", name="执行查询").click()
        self.assertTrue(self.page.locator("#result-success").is_visible())
        self.page.get_by_role("button", name="重置").click()
        self.assertTrue(self.page.locator("#result-empty").is_visible())
        self.assertEqual(self.page.locator("#binding-revision").input_value(), "1")
        self.assertIn("revision 1", self.page.locator("#binding-context").inner_text())

    def test_mobile_layout_keeps_page_inside_viewport(self):
        self.page.set_viewport_size({"width": 390, "height": 844})
        self.assertTrue(self.page.get_by_role("heading", name="对象集合查询", exact=True).is_visible())
        self.assertLessEqual(
            self.page.evaluate("document.documentElement.scrollWidth"),
            self.page.evaluate("window.innerWidth"),
            "main page should not overflow horizontally; table can scroll inside its own container",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
