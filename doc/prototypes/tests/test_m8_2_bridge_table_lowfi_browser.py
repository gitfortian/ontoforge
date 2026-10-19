"""Real Chromium behavior checks for the standalone M8.2 UX-Low synthetic prototype.

This tests frontend product states only. It does NOT test production Postgres,
authorization, live API, JDBC, transactional isolation or actual LinkFacts.
"""
from __future__ import annotations
import os
import unittest
from pathlib import Path
from playwright.sync_api import sync_playwright

PROTOTYPE = Path(__file__).resolve().parents[1] / "m8-2-bridge-table-lowfi.html"


class M82BridgeTableUXLowBrowser(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pw = sync_playwright().start()
        cls.browser = cls.pw.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.pw.stop()

    def setUp(self):
        self.page = self.browser.new_page(viewport={"width": 1440, "height": 950}, locale="zh-CN")
        # A synchronous static prototype must never leave a 30s implicit locator wait.
        self.page.set_default_timeout(2500)
        self.page.set_default_navigation_timeout(5000)
        self.errors = []
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))
        self.page.goto(PROTOTYPE.as_uri())
        self.page.get_by_role("heading", name="配置桥表实例来源").wait_for()
        self.page.wait_for_function("typeof window.__m82uxLow?.getState === 'function'", timeout=3500)

    def tearDown(self):
        directory = os.environ.get("UX_SCREENSHOT_DIR")
        if directory and (self.errors or os.environ.get("UX_CAPTURE_SCREENSHOTS")):
            target = Path(directory)
            target.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(
                path=str(target / (self.id().rsplit(".", 1)[-1] + ".png")), full_page=True
            )
        self.assertEqual([], self.errors, f"Uncaught JS errors: {self.errors}")
        self.page.close()

    def model(self):
        return self.page.evaluate("window.__m82uxLow.getState()")

    def validate(self, scenario="clean"):
        self.page.locator("#to-validate").click()
        self.page.locator("#validation-scenario").select_option(scenario)
        self.page.locator("#run-validation").click()

    def save(self, scenario="clean", fault="none"):
        self.validate(scenario)
        self.page.locator("#continue-save").click()
        self.page.locator("#save-fault").select_option(fault)
        self.page.locator("#commit-binding").click()

    def query(self, direction="OUT", root="MO-001", fault="none", mode="single",
              operation="PAGE", filter="all"):
        self.page.locator("#tab-query").click()
        self.page.locator("#query-direction").select_option(direction)
        self.page.locator("#root-mode").select_option(mode)
        if mode == "single":
            self.page.locator("#query-root").select_option(root)
        self.page.locator("#result-operation").select_option(operation)
        self.page.locator("#target-filter").select_option(filter)
        self.page.locator("#query-fault").select_option(fault)
        self.page.locator("#run-query").click()

    def test_enters_from_existing_native_relation_type_not_separate_tool_menu(self):
        self.page.locator("#nav-type").click()
        self.assertIn("尚未绑定实例关系", self.page.locator("#type-status").inner_text())
        self.page.locator("#open-config").click()
        self.assertTrue(self.page.locator("#view-config").is_visible())
        self.assertEqual("config", self.model()["view"])

    def test_query_without_saved_binding_never_invents_edges(self):
        self.query()
        self.assertIn("尚未配置 LinkBinding", self.page.locator("#query-outcome").inner_text())
        self.assertTrue(self.page.locator("#result-panel").is_hidden())
        self.assertFalse(self.model()["saved"])

    def test_bridge_table_is_explicit_third_source_not_third_object_binding(self):
        self.assertIn("BRIDGE_TABLE", self.page.locator("#view-config").inner_text())
        self.assertIn("MaintenanceOrder", self.page.locator("#view-config").inner_text())
        self.assertIn("Equipment", self.page.locator("#view-config").inner_text())
        self.assertEqual(2, self.page.locator("#view-config select[id$='binding']").count())
        self.assertTrue(self.page.locator("#bridge-table").is_visible())

    def test_wrong_snapshot_and_cross_source_are_rejected(self):
        self.page.locator("#source-binding").select_option("foreign")
        self.validate()
        self.assertIn("LINK_ENDPOINT_VERSION_MISMATCH", self.page.locator("#validation-outcome").inner_text())
        self.assertTrue(self.page.locator("#continue-save").is_disabled())
        self.page.locator("#back-to-config").click()
        self.page.locator("#source-binding").select_option("B-MO:r2")
        self.page.locator("#bridge-table").select_option("cross-source")
        self.validate()
        self.assertIn("BRIDGE_SOURCE_MISMATCH", self.page.locator("#validation-outcome").inner_text())
        self.assertTrue(self.page.locator("#continue-save").is_disabled())

    def test_unallowlisted_bridge_table_cannot_leak_or_save(self):
        self.page.locator("#bridge-table").select_option("unknown")
        self.validate()
        self.assertIn("SOURCE_NOT_ALLOWED", self.page.locator("#validation-outcome").inner_text())
        self.assertTrue(self.page.locator("#continue-save").is_disabled())

    def test_reverse_fk_and_wrong_type_are_rejected(self):
        for column, value in [("source-fk", "equipment_code"), ("target-fk", "bad-type")]:
            self.page.locator("#back-to-config").click() if self.model()["view"] == "validate" else None
            self.page.locator("#" + column).select_option(value)
            self.validate()
            self.assertIn("LINK_BRIDGE_COLUMN_INCOMPATIBLE", self.page.locator("#validation-outcome").inner_text())
            self.assertTrue(self.page.locator("#continue-save").is_disabled())

    def test_valid_full_ledger_is_three_source_and_read_only(self):
        self.validate("clean")
        self.assertIn("VALID · FULL", self.page.locator("#validation-outcome").inner_text())
        self.assertIn("3 个逻辑 pair", self.page.locator("#validation-pairs").inner_text())
        self.assertIn("重复 0", self.page.locator("#validation-quality").inner_text())
        self.assertIn("REPEATABLE_READ", self.page.locator("#validation-coverage").inner_text())
        self.assertTrue(self.model()["validated"])
        self.assertTrue(self.page.locator("#continue-save").is_enabled())

    def test_full_quality_buckets_are_mutually_exclusive_and_accounted(self):
        self.validate("orphan")
        ledger = self.page.locator("#validation-buckets").inner_text()
        self.assertIn("SOURCE 孤儿 1", ledger)
        self.assertIn("TARGET 孤儿 1", ledger)
        self.assertIn("有效物理行 3 = 总 5", ledger)
        self.page.locator("#validation-scenario").select_option("null")
        self.page.locator("#run-validation").click()
        ledger = self.page.locator("#validation-buckets").inner_text()
        self.assertIn("双 NULL 1", ledger)
        self.assertIn("仅 SOURCE NULL 1", ledger)
        self.assertIn("仅 TARGET NULL 1", ledger)
        self.assertIn("有效物理行 3 = 总 6", ledger)

    def test_success_save_button_does_not_duplicate_immutable_revision(self):
        self.save()
        self.assertEqual(1, self.model()["revision"])
        self.assertTrue(self.page.locator("#commit-binding").is_disabled())
        self.page.locator("#reopen-revision").click()
        self.assertEqual(1, self.model()["revision"])

    def test_duplicate_bridge_pair_is_not_extra_logical_link(self):
        self.save("duplicate")
        self.assertEqual("duplicate", self.model()["savedScenario"])
        self.query()
        self.assertEqual(2, self.model()["objectCount"])
        self.assertEqual(2, self.model()["linkCount"])
        self.assertEqual(1, self.model()["duplicates"])
        self.assertIn("Bridge 重复物理行 1", self.page.locator("#result-summary").inner_text())

    def test_duplicate_pair_evidence_respects_output_filter_scope(self):
        self.save("duplicate")
        self.query("OUT", "MO-001", filter="E02")
        self.assertEqual(1, self.model()["objectCount"])
        self.assertEqual(1, self.model()["linkCount"])
        self.assertEqual(0, self.model()["duplicates"], "Filtered-out E01 repeats cannot count as E02 evidence")

    def test_selected_historical_endpoint_revision_is_visible_in_three_source_evidence(self):
        self.page.locator("#source-binding").select_option("B-MO:r1")
        self.save()
        self.query()
        self.page.locator("#open-evidence").click()
        self.assertIn("B-MO:r1", self.page.locator("#evidence-source-version").inner_text())
        self.assertIn("B-EQ:r3", self.page.locator("#evidence-target-version").inner_text())

    def test_null_fk_not_an_orphan_or_failure(self):
        self.save("null")
        self.assertEqual("VALID", self.model()["validationQuality"])
        self.query("OUT", "MO-002")
        self.assertEqual("COMPLETE / REQUESTED_SCOPE", self.model()["qualityResult"])
        self.assertEqual(1, self.model()["linkCount"])

    def test_orphan_full_is_savable_and_partial_only_for_affected_out_root(self):
        self.save("orphan")
        self.assertEqual("VALID_WITH_ORPHANS", self.model()["validationQuality"])
        self.query("OUT", "MO-001")
        self.assertIn("PARTIAL / KNOWN_SUBSET", self.model()["qualityResult"])
        self.assertIn("当前根有 1 个可确认孤儿", self.page.locator("#query-outcome").inner_text())
        self.query("OUT", "MO-002")
        self.assertIn("COMPLETE", self.model()["qualityResult"])

    def test_orphan_in_scope_is_not_same_as_out_scope(self):
        self.save("orphan")
        self.query("IN", "E02")
        self.assertIn("PARTIAL / KNOWN_SUBSET", self.model()["qualityResult"])
        self.assertEqual(2, self.model()["objectCount"])
        self.query("IN", "E01")
        self.assertIn("COMPLETE", self.model()["qualityResult"])

    def test_budget_full_is_not_validated_sample(self):
        self.validate("budget")
        self.assertIn("QUERY_BUDGET_EXCEEDED", self.page.locator("#validation-outcome").inner_text())
        self.assertIn("SAMPLE / UNKNOWN_TOTAL", self.page.locator("#validation-coverage").inner_text())
        self.assertTrue(self.page.locator("#continue-save").is_disabled())
        self.assertFalse(self.model()["validated"])

    def test_revoked_bridge_and_schema_drift_fail_full(self):
        self.validate("revoke")
        self.assertIn("403 SOURCE_NOT_ALLOWED", self.page.locator("#validation-outcome").inner_text())
        self.assertNotIn("VALID_WITH_ORPHANS", self.page.locator("#validation-outcome").inner_text())
        self.page.locator("#validation-scenario").select_option("drift")
        self.page.locator("#run-validation").click()
        self.assertIn("LINK_SOURCE_SCHEMA_DRIFT", self.page.locator("#validation-outcome").inner_text())
        self.assertFalse(self.model()["validated"])

    def test_cas_conflict_does_not_advance_revision(self):
        self.save(fault="cas")
        self.assertIn("BINDING_REVISION_CONFLICT", self.page.locator("#save-outcome").inner_text())
        self.assertEqual(0, self.model()["revision"])
        self.assertFalse(self.model()["saved"])

    def test_unknown_save_recovers_exactly_one_revision(self):
        self.save(fault="unknown")
        self.assertIn("UNKNOWN", self.page.locator("#save-outcome").inner_text())
        op=self.model()["operationId"]
        self.page.locator("#lookup-operation").click()
        self.assertEqual(op, self.model()["operationId"])
        self.assertEqual(1, self.model()["revision"])
        self.page.locator("#reopen-revision").click()
        self.assertIn("重开已保存修订 r1", self.page.locator("#save-outcome").inner_text())
        self.assertEqual(1, self.model()["revision"])

    def test_source_fingerprint_drift_at_save_requires_revalidation(self):
        self.save(fault="drift")
        self.assertIn("LINK_SOURCE_SCHEMA_DRIFT", self.page.locator("#save-outcome").inner_text())
        self.assertFalse(self.model()["saved"])
        self.assertTrue(self.page.locator("#commit-binding").is_disabled())

    def test_out_in_original_linkfact_direction_and_stable_pagination(self):
        self.save()
        self.query("OUT", "MO-001")
        self.assertIn("MO-001 → E01", self.page.locator("#result-rows").inner_text())
        first=self.model()["snapshot"]
        self.page.locator("#next-page").click()
        self.assertEqual(first, self.model()["snapshot"])
        self.assertIn("MO-001 → E02", self.page.locator("#result-rows").inner_text())
        self.query("IN", "E02")
        self.assertIn("MO-001 → E02", self.page.locator("#result-rows").inner_text())
        self.assertNotEqual(first, self.model()["snapshot"])

    def test_bounded_root_set_deduplicates_objects_but_preserves_links(self):
        self.save()
        self.query(mode="bounded")
        self.assertEqual(2, self.model()["objectCount"])
        self.assertEqual(3, self.model()["linkCount"])
        self.assertIn("根集合 MO-001、MO-002", self.page.locator("#root-scope").inner_text())

    def test_count_distinct_and_sum_do_not_double_count_physical_bridge_rows(self):
        self.save("duplicate")
        self.query(operation="COUNT_DISTINCT")
        self.assertIn("= 2", self.page.locator("#aggregate-panel").inner_text())
        self.query(operation="SUM")
        self.assertIn("= 30", self.page.locator("#aggregate-panel").inner_text())
        self.assertTrue(self.page.locator("#next-page").is_disabled())

    def test_true_zero_does_not_look_like_failure_or_partial(self):
        self.save()
        self.query("OUT", "MO-003")
        self.assertIn("SUCCESS · 真实零关联", self.page.locator("#query-outcome").inner_text())
        self.assertEqual(0, self.model()["linkCount"])
        self.assertIn("COMPLETE", self.model()["qualityResult"])

    def test_bridge_source_revoke_invalidates_snapshot_and_hides_payload(self):
        self.save()
        self.query("OUT", "MO-001")
        frozen=self.model()["snapshot"]
        self.page.locator("#query-fault").select_option("revoke-bridge")
        self.page.locator("#next-page").click()
        self.assertTrue(self.page.locator("#result-panel").is_hidden())
        self.assertIn(frozen, self.model()["retired"])
        self.page.locator("#nav-evidence").click()
        self.assertIn("旧结果已失效", self.page.locator("#failure-evidence").inner_text())
        self.page.locator("#retry-query").click()
        self.assertNotEqual(frozen, self.model()["snapshot"])

    def test_bridge_drift_invalidates_old_page_after_source_changes(self):
        self.save()
        self.query()
        self.page.locator("#query-fault").select_option("drift-bridge")
        self.page.locator("#next-page").click()
        self.assertIn("LINK_SOURCE_SCHEMA_DRIFT", self.page.locator("#query-outcome").inner_text())
        self.assertTrue(self.page.locator("#result-panel").is_hidden())

    def test_timeout_and_budget_fail_without_objects_or_cursor(self):
        self.save()
        self.query(fault="timeout")
        self.assertIn("QUERY_TIMEOUT", self.page.locator("#query-outcome").inner_text())
        self.assertIsNone(self.model()["snapshot"])
        self.assertTrue(self.page.locator("#result-panel").is_hidden())
        self.query(fault="budget")
        self.assertIn("QUERY_BUDGET_EXCEEDED", self.page.locator("#query-outcome").inner_text())
        self.assertTrue(self.page.locator("#result-panel").is_hidden())

    def test_expired_cursor_requires_new_execution(self):
        self.save()
        self.query()
        first=self.model()["snapshot"]
        self.page.locator("#query-fault").select_option("expired")
        self.page.locator("#next-page").click()
        self.assertIn("RESULT_EXPIRED", self.page.locator("#query-outcome").inner_text())
        self.assertIn(first, self.model()["retired"])

    def test_unsaved_navigation_cancel_then_confirm_discard(self):
        self.page.locator("#bridge-table").select_option("unknown")
        self.assertTrue(self.model()["dirty"])
        self.page.once("dialog", lambda dialog: dialog.dismiss())
        self.page.locator("#nav-query").click()
        self.assertEqual("config", self.model()["view"])
        self.page.once("dialog", lambda dialog: dialog.accept())
        self.page.locator("#nav-query").click()
        self.assertEqual("query", self.model()["view"])
        self.assertFalse(self.model()["dirty"])
        self.page.locator("#nav-config").click()
        self.assertEqual("maintenance_order_equipment", self.page.locator("#bridge-table").input_value())

    def test_discard_unsaved_restores_persisted_binding_revision(self):
        self.save()
        self.page.locator("#tab-config").click()
        self.page.locator("#target-binding").select_option("B-EQ:r2")
        self.assertTrue(self.model()["dirty"])
        self.page.once("dialog", lambda dialog: dialog.accept())
        self.page.locator("#nav-query").click()
        self.page.locator("#nav-config").click()
        self.assertEqual("B-EQ:r3", self.page.locator("#target-binding").input_value())
        self.assertFalse(self.model()["dirty"])

    def test_narrow_375px_viewport_has_no_horizontal_overflow(self):
        self.page.set_viewport_size({"width": 375, "height": 812})
        self.assertTrue(self.page.locator("#view-config").is_visible())
        self.assertFalse(self.page.evaluate("document.documentElement.scrollWidth > window.innerWidth + 2"))
        self.page.locator("#to-validate").click()
        self.assertFalse(self.page.evaluate("document.documentElement.scrollWidth > window.innerWidth + 2"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
