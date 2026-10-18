"""Chromium regression checks for the M7 ObjectSet UX-High static prototype."""
from __future__ import annotations

import re
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

    def execute(self):
        self.page.get_by_role("button", name="执行查询").click()

    def snapshot_id(self):
        match = re.search(r"result-local-\d+", self.page.locator("#evidence-snapshot").inner_text())
        self.assertIsNotNone(match, "a successful execution must expose its own result snapshot")
        return match.group(0)

    def test_high_fidelity_shell_and_unknown_freshness_are_visible(self):
        self.assertIn("UX-High", self.page.locator("body").inner_text())
        self.assertIn("UNKNOWN", self.page.locator(".context").inner_text())
        self.assertIn("不调用后端 API", self.page.locator(".notice").inner_text())
        self.assertTrue(self.page.get_by_role("button", name="执行查询").is_visible())

    def test_default_filter_actually_excludes_null_rating_and_reports_exact_count(self):
        self.execute()
        self.assertTrue(self.page.locator("#result-success").is_visible())
        self.assertEqual(self.page.locator("#metric-count").inner_text(), "9")
        self.assertEqual(self.page.locator("#result-rows tr").count(), 9)
        self.assertNotIn("Westlake Supply", self.page.locator("#result-rows").inner_text())
        self.assertEqual(self.page.locator("#metric-page").inner_text(), "1 / 1")

    def test_changing_filter_changes_the_result_set(self):
        self.page.locator("#filter-value-0").fill("4.5")
        self.execute()
        self.assertEqual(self.page.locator("#metric-count").inner_text(), "4")
        self.assertEqual(self.page.locator("#result-rows tr").count(), 4)
        self.assertNotIn("Summit Materials", self.page.locator("#result-rows").inner_text())

    def test_sort_direction_and_projection_drive_rendered_table(self):
        self.page.locator("#sort-direction-0").select_option("ASC")
        self.page.locator('#project-fields input[value="name"]').uncheck()
        self.page.locator('#project-fields input[value="active"]').check()
        self.execute()
        self.assertEqual(self.page.locator('#result-rows td[data-field="name"]').count(), 0)
        self.assertEqual(self.page.locator('#result-head th[data-field="name"]').count(), 0)
        self.assertEqual(self.page.locator('#result-head th[data-field="active"]').count(), 1)
        self.assertEqual(self.page.locator('#result-rows td[data-field="active"]').count(), 9)
        # The first visible field is now the lowest matching rating, proving ASC is applied.
        self.assertEqual(self.page.locator("#result-rows td[data-field='objectRef']").first.inner_text(), "supplier:109")

    def test_sort_rules_are_limited_to_three(self):
        self.page.get_by_role("button", name="添加排序").click()
        self.page.get_by_role("button", name="添加排序").click()
        self.assertEqual(self.page.locator("[data-sort-row]").count(), 3)
        self.assertTrue(self.page.locator("#add-sort").is_disabled())

    def test_filter_operator_choices_follow_property_type(self):
        self.page.locator("#filter-property-0").select_option("name")
        self.assertEqual(self.page.locator('#filter-op-0 option[value="GT"]').count(), 0)
        self.assertGreater(self.page.locator('#filter-op-0 option[value="PREFIX"]').count(), 0)
        self.page.locator("#filter-property-0").select_option("rating")
        self.assertGreater(self.page.locator('#filter-op-0 option[value="GT"]').count(), 0)

    def test_pagination_keeps_one_snapshot_and_new_execution_gets_a_new_one(self):
        self.page.locator("#page-size").select_option("3")
        self.execute()
        first_snapshot = self.snapshot_id()
        self.assertEqual(self.page.locator("#result-rows tr").count(), 3)
        self.page.get_by_role("button", name="下一页").click()
        self.assertEqual(self.page.locator("#metric-page").inner_text(), "2 / 3")
        self.assertEqual(self.snapshot_id(), first_snapshot)
        self.page.get_by_role("button", name="下一页").click()
        self.assertEqual(self.page.locator("#metric-page").inner_text(), "3 / 3")
        self.assertEqual(self.page.locator("#result-rows tr").count(), 3)
        self.page.locator("#filter-value-0").fill("4.5")
        self.execute()
        self.assertNotEqual(self.snapshot_id(), first_snapshot)
        self.assertEqual(self.page.locator("#metric-count").inner_text(), "4")

    def test_aggregate_is_exclusive_with_sort_page_and_projection(self):
        self.page.get_by_role("button", name="聚合统计").click()
        self.assertTrue(self.page.locator("#aggregate-options").is_visible())
        self.assertFalse(self.page.locator("#page-options").is_visible())
        self.assertFalse(self.page.locator("#sort-step").is_visible())
        self.assertFalse(self.page.locator("#project-step").is_visible())
        self.assertTrue(self.page.locator("#aggregate-property-wrap").is_hidden())
        self.execute()
        self.assertTrue(self.page.locator("#aggregate-result").is_visible())
        self.assertFalse(self.page.locator("#objects-table").is_visible())
        self.assertEqual(self.page.locator("#aggregate-value").inner_text(), "9")
        self.page.locator("#aggregate-op").select_option("SUM")
        self.page.locator("#aggregate-property").select_option("annualSpend")
        self.execute()
        self.assertEqual(self.page.locator("#aggregate-label").inner_text(), "SUM(annualSpend)")
        self.assertEqual(self.page.locator("#aggregate-value").inner_text(), "1275300.00")

    def test_sum_fails_closed_when_matching_input_contains_null(self):
        self.page.get_by_role("button", name="聚合统计").click()
        self.page.locator("#aggregate-op").select_option("SUM")
        self.page.locator("#aggregate-property").select_option("rating")
        self.page.locator("#filter-op-0").select_option("IS_NULL")
        self.execute()
        self.assertIn("422 AGGREGATE_VALUE_UNAVAILABLE", self.page.locator("#error-result").inner_text())
        self.assertTrue(self.page.locator("#result-success").is_hidden())
        self.assertTrue(self.page.locator("#result-evidence").is_visible())
        self.assertEqual(self.page.locator("#aggregate-value").inner_text(), "—")

    def test_invalid_numeric_literal_fails_closed(self):
        self.page.locator("#filter-value-0").fill("not-a-number")
        self.execute()
        self.assertIn("422 INVALID_QUERY_AST", self.page.locator("#error-result").inner_text())
        self.assertTrue(self.page.locator("#result-success").is_hidden())
        self.assertTrue(self.page.locator("#result-evidence").is_visible())
        self.assertEqual(self.page.locator("#result-rows tr").count(), 0)

    def test_budget_source_drift_and_expiry_never_return_rows(self):
        for scenario, code in [
            ("budget", "429 QUERY_BUDGET_EXCEEDED"),
            ("unavailable", "503 SOURCE_UNAVAILABLE"),
            ("drift", "409 BINDING_SCHEMA_DRIFT"),
            ("expired", "410 RESULT_EXPIRED"),
        ]:
            with self.subTest(scenario=scenario):
                self.page.locator("#scenario").select_option(scenario)
                self.execute()
                self.assertIn(code, self.page.locator("#error-result").inner_text())
                self.assertTrue(self.page.locator("#result-success").is_hidden())
                self.assertTrue(self.page.locator("#result-evidence").is_visible())
                self.assertEqual(self.page.locator("#result-rows tr").count(), 0)

    def test_permission_revocation_between_pages_invalidates_old_result(self):
        self.page.locator("#scenario").select_option("page-revoked")
        self.page.locator("#page-size").select_option("3")
        self.execute()
        self.assertTrue(self.page.locator("#result-success").is_visible())
        old_snapshot = self.snapshot_id()
        self.page.get_by_role("button", name="下一页").click()
        self.assertIn("503 SOURCE_UNAVAILABLE", self.page.locator("#error-result").inner_text())
        self.assertTrue(self.page.locator("#result-success").is_hidden())
        self.assertTrue(self.page.locator("#result-evidence").is_visible())
        self.assertEqual(self.page.locator("#result-rows tr").count(), 0)
        self.assertIn("已失效", self.page.locator("#evidence-snapshot").inner_text())
        self.assertIn(old_snapshot, self.page.locator("#evidence-snapshot").inner_text())
        self.assertIn("未返回结果载荷", self.page.locator("#evidence-access").inner_text())

    def test_exact_empty_result_is_not_reported_as_failure(self):
        self.page.locator("#filter-value-0").fill("9.9")
        self.execute()
        self.assertTrue(self.page.locator("#result-success").is_visible())
        self.assertTrue(self.page.locator("#empty-results").is_visible())
        self.assertTrue(self.page.locator("#error-result").is_hidden())
        self.assertEqual(self.page.locator("#metric-count").inner_text(), "0")
        self.assertEqual(self.page.locator("#result-rows tr").count(), 0)

    def test_revision_change_invalidates_previous_result(self):
        self.execute()
        self.assertTrue(self.page.locator("#result-success").is_visible())
        self.page.locator("#binding-revision").select_option("2")
        self.assertTrue(self.page.locator("#result-success").is_hidden())
        self.assertTrue(self.page.locator("#result-evidence").is_hidden())
        self.assertIn("revision 2", self.page.locator("#binding-context").inner_text())
        self.assertEqual(self.page.locator("#result-status").inner_text(), "尚未执行")
        self.assertNotIn("green", self.page.locator("#result-status").get_attribute("class"))

    def test_mobile_has_no_document_horizontal_overflow(self):
        self.page.set_viewport_size({"width": 390, "height": 844})
        self.assertLessEqual(
            self.page.evaluate("document.documentElement.scrollWidth"),
            self.page.evaluate("innerWidth"),
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
