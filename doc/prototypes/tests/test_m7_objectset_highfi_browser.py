"""Chromium regression checks for the M7 ObjectSet UX-High static prototype."""
from __future__ import annotations
import unittest
from pathlib import Path
from playwright.sync_api import sync_playwright

PAGE = Path(__file__).resolve().parents[1] / "m7-objectset-highfi.html"

class M7ObjectSetHighFiBrowser(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pw = sync_playwright().start()
        cls.browser = cls.pw.chromium.launch(headless=True, args=["--no-sandbox"])
    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.pw.stop()
    def setUp(self):
        self.page = self.browser.new_page(viewport={"width": 1440, "height": 1000}, locale="zh-CN")
        self.errors = []
        self.page.on("pageerror", lambda e: self.errors.append(str(e)))
        self.page.goto(PAGE.as_uri())
        self.page.get_by_role("heading", name="对象集合查询", exact=True).wait_for()
    def tearDown(self):
        self.assertEqual(self.errors, [])
        self.page.close()
    def test_high_fidelity_shell_and_unknown_freshness_are_visible(self):
        self.assertIn("UX-High", self.page.locator("body").inner_text())
        self.assertIn("UNKNOWN", self.page.locator(".context").inner_text())
        self.assertIn("不调用后端 API", self.page.locator(".notice").inner_text())
        self.assertTrue(self.page.get_by_role("button", name="执行查询").is_visible())
    def test_execute_and_stable_pagination(self):
        self.page.locator("#page-size").select_option("3")
        self.page.get_by_role("button", name="执行查询").click()
        self.assertTrue(self.page.locator("#result-success").is_visible())
        self.assertEqual(self.page.locator("#result-rows tr").count(), 3)
        self.page.get_by_role("button", name="下一页").click()
        self.assertEqual(self.page.locator("#metric-page").inner_text(), "2 / 4")
        self.page.get_by_role("button", name="上一页").click()
        self.assertEqual(self.page.locator("#metric-page").inner_text(), "1 / 4")
    def test_aggregate_is_mutually_exclusive_with_page_controls(self):
        self.page.get_by_role("button", name="聚合统计").click()
        self.assertTrue(self.page.locator("#aggregate-options").is_visible())
        self.assertFalse(self.page.locator("#page-options").is_visible())
        self.page.get_by_role("button", name="执行查询").click()
        self.assertTrue(self.page.locator("#aggregate-result").is_visible())
        self.assertFalse(self.page.locator("#objects-table").is_visible())
    def test_invalid_typed_filter_fails_closed(self):
        self.page.locator("#filter-property-0").select_option("name")
        self.page.locator("#filter-op-0").select_option("GT")
        self.page.get_by_role("button", name="执行查询").click()
        self.assertIn("422 INVALID_QUERY_AST", self.page.locator("#error-result").inner_text())
        self.assertTrue(self.page.locator("#result-success").evaluate("(e)=>e.classList.contains('hidden')"))
    def test_budget_source_drift_and_expiry_never_return_rows(self):
        for scenario, code in [("budget","429 QUERY_BUDGET_EXCEEDED"),("unavailable","503 SOURCE_UNAVAILABLE"),("drift","409 BINDING_SCHEMA_DRIFT"),("expired","410 RESULT_EXPIRED")]:
            with self.subTest(scenario=scenario):
                self.page.locator("#scenario").select_option(scenario)
                self.page.get_by_role("button", name="执行查询").click()
                self.assertIn(code, self.page.locator("#error-result").inner_text())
                self.assertTrue(self.page.locator("#result-success").evaluate("(e)=>e.classList.contains('hidden')"))
    def test_revision_change_invalidates_previous_result(self):
        self.page.get_by_role("button", name="执行查询").click()
        self.assertTrue(self.page.locator("#result-success").is_visible())
        self.page.locator("#binding-revision").select_option("2")
        self.assertTrue(self.page.locator("#result-success").evaluate("(e)=>e.classList.contains('hidden')"))
        self.assertIn("revision 2", self.page.locator("#binding-context").inner_text())
    def test_mobile_has_no_document_horizontal_overflow(self):
        self.page.set_viewport_size({"width":390,"height":844})
        self.assertLessEqual(self.page.evaluate("document.documentElement.scrollWidth"), self.page.evaluate("innerWidth"))
if __name__ == "__main__":
    unittest.main(verbosity=2)
