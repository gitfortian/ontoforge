"""M8.1 static UX-High interactive design review in a real Chromium browser.

Only tests the synthetic interaction prototype. Does NOT validate production FK/PG/JWT.
"""
from __future__ import annotations

import os
import unittest
from pathlib import Path
from playwright.sync_api import sync_playwright

PROTOTYPE = Path(__file__).resolve().parents[1] / "m8-1-linkbinding-highfi.html"


class M81LinkBindingUXHighBrowser(unittest.TestCase):
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
        self.errors = []
        self.page.on("pageerror", lambda exc: self.errors.append(str(exc)))
        self.page.goto(PROTOTYPE.as_uri())
        self.page.get_by_role("heading", name="实例关联配置").wait_for()

    def tearDown(self):
        directory = os.environ.get("UX_SCREENSHOT_DIR")
        if directory and (self.errors or os.environ.get("UX_CAPTURE_SCREENSHOTS")):
            dest = Path(directory)
            dest.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(dest / (self.id().rsplit(".", 1)[-1] + ".png")), full_page=True)
        self.assertEqual([], self.errors, f"Javascript page errors: {self.errors}")
        self.page.close()

    def model(self):
        return self.page.evaluate("window.__uxHighDemo.getState()")

    def validate(self, scenario="valid"):
        self.page.locator("#config-validate").click()
        self.page.locator("#validation-scenario").select_option(scenario)
        self.page.locator("#run-validation").click()

    def save(self, scenario="normal"):
        self.validate(scenario if scenario in ("valid", "orphans", "null") else "valid")
        self.page.locator("#continue-save").click()
        self.page.locator("#save-scenario").select_option(scenario if scenario in ("cas", "unknown", "changed") else "normal")
        self.page.locator("#save-binding").click()

    def query(self, direction="OUT", root="EQ-001", fault="none"):
        self.page.locator("#tab-query").click()
        self.page.locator("#query-direction").select_option(direction)
        self.page.locator("#query-root").select_option(root)
        self.page.locator("#query-fault").select_option(fault)
        self.page.locator("#run-query").click()

    def test_existing_navigation_is_entry_not_new_tool_menu(self):
        self.page.locator("#nav-type").click()
        self.assertIn("尚无 LinkBinding", self.page.locator("#type-status").inner_text())
        self.page.locator("#type-continue").click()
        self.assertTrue(self.page.locator("#view-config").is_visible())
        self.assertFalse(self.page.locator("#view-query").is_visible())

    def test_cannot_query_before_link_binding_is_saved(self):
        self.query()
        self.assertIn("尚未配置", self.page.locator("#query-outcome").inner_text())
        self.assertTrue(self.page.locator("#result-box").is_hidden())

    def test_fk_side_mismatch_fails_and_cannot_save(self):
        self.page.locator("#fk-owner").select_option("SOURCE")
        self.validate()
        self.assertIn("INVALID_LINK_MAPPING", self.page.locator("#validation-outcome").inner_text())
        self.assertTrue(self.page.locator("#continue-save").is_disabled())

    def test_wrong_snapshot_or_column_fails_closed(self):
        self.page.locator("#source-binding").select_option("bad")
        self.validate()
        self.assertIn("FAILED", self.page.locator("#validation-outcome").inner_text())
        self.page.locator("#tab-config").click()
        self.page.locator("#source-binding").select_option("B-11:r2")
        self.page.locator("#fk-column").select_option("missing_column")
        self.validate()
        self.assertIn("422", self.page.locator("#validation-outcome").inner_text())
        self.assertFalse(self.model()["validated"])

    def test_full_orphan_can_be_saved_but_sample_budget_cannot(self):
        self.validate("budget")
        self.assertIn("429", self.page.locator("#validation-outcome").inner_text())
        self.assertIn("SAMPLE", self.page.locator("#validation-scope").inner_text())
        self.assertTrue(self.page.locator("#continue-save").is_disabled())
        self.page.locator("#validation-scenario").select_option("orphans")
        self.page.locator("#run-validation").click()
        self.assertIn("VALID_WITH_ORPHANS", self.page.locator("#validation-outcome").inner_text())
        self.page.locator("#continue-save").click()
        self.page.locator("#save-binding").click()
        self.assertTrue(self.model()["saved"])
        self.assertEqual("VALID_WITH_ORPHANS", self.model()["quality"])

    def test_cardinality_conflict_and_source_revocation_block_save(self):
        self.validate("conflict")
        self.assertIn("LINK_CARDINALITY_CONFLICT", self.page.locator("#validation-outcome").inner_text())
        self.assertTrue(self.page.locator("#continue-save").is_disabled())
        self.page.locator("#validation-scenario").select_option("source")
        self.page.locator("#run-validation").click()
        self.assertIn("SOURCE_UNAVAILABLE", self.page.locator("#validation-outcome").inner_text())
        self.assertTrue(self.page.locator("#continue-save").is_disabled())

    def test_cas_conflict_preserves_unsaved_and_unknown_save_is_idempotent(self):
        self.save("cas")
        self.assertIn("BINDING_REVISION_CONFLICT", self.page.locator("#save-outcome").inner_text())
        self.assertFalse(self.model()["saved"])
        self.page.locator("#save-scenario").select_option("unknown")
        self.page.locator("#save-binding").click()
        self.assertIn("UNKNOWN", self.page.locator("#save-outcome").inner_text())
        self.page.locator("#lookup-save").click()
        self.assertEqual(1, self.model()["revision"])
        self.page.locator("#reload-saved").click()
        self.assertIn("修订 r1", self.page.locator("#save-outcome").inner_text())
        self.assertEqual(1, self.model()["revision"])

    def test_source_drift_during_save_requires_revalidation(self):
        self.save("changed")
        self.assertIn("BINDING_SCHEMA_DRIFT", self.page.locator("#save-outcome").inner_text())
        self.assertTrue(self.page.locator("#save-binding").is_disabled())
        self.assertFalse(self.model()["saved"])

    def test_correct_out_in_direction_and_stable_snapshot_paging(self):
        self.save()
        self.page.locator("#to-query").click()
        self.query("OUT", "EQ-001")
        self.assertIn("2 个去重对象", self.page.locator("#query-outcome").inner_text())
        self.assertIn("LOT-11", self.page.locator("#result-rows").inner_text())
        snapshot = self.model()["snapshot"]
        self.page.locator("#next-page").click()
        self.assertEqual(snapshot, self.model()["snapshot"])
        self.assertIn("LOT-12", self.page.locator("#result-rows").inner_text())
        self.query("IN", "LOT-11")
        self.assertIn("EQ-001 → LOT-11", self.page.locator("#result-rows").inner_text())
        self.assertNotEqual(snapshot, self.model()["snapshot"])

    def test_global_orphan_does_not_contaminate_unrelated_root(self):
        self.validate("orphans")
        self.page.locator("#continue-save").click()
        self.page.locator("#save-binding").click()
        self.query("OUT", "EQ-001")
        self.assertIn("COMPLETE", self.model()["qualityResult"])
        self.query("IN", "LOT-14")
        self.assertIn("PARTIAL / KNOWN_SUBSET", self.model()["qualityResult"])
        self.assertIn("可确认断链", self.page.locator("#query-outcome").inner_text())
        self.assertIn("对象 0", self.page.locator("#result-summary").inner_text())

    def test_null_and_empty_result_are_success_not_failure(self):
        self.save()
        self.query("OUT", "EQ-002")
        self.assertIn("SUCCESS · 真实零关联", self.page.locator("#query-outcome").inner_text())
        self.query("IN", "LOT-13")
        self.assertIn("SUCCESS · 真实零关联", self.page.locator("#query-outcome").inner_text())
        self.assertIn("COMPLETE", self.model()["qualityResult"])

    def test_revoke_and_drift_hide_payload_and_retire_cursor(self):
        self.save()
        self.query()
        previous_snapshot = self.model()["snapshot"]
        self.page.locator("#query-fault").select_option("revoke")
        self.page.locator("#next-page").click()
        self.assertTrue(self.page.locator("#result-box").is_hidden())
        self.assertIn(previous_snapshot, self.model()["retired"])
        self.page.locator("#tab-evidence").click()
        self.assertIn("旧结果已失效", self.page.locator("#failure-evidence").inner_text())
        self.page.locator("#retry-query").click()
        self.assertNotEqual(previous_snapshot, self.model()["snapshot"])
        self.page.locator("#query-fault").select_option("drift")
        self.page.locator("#next-page").click()
        self.assertIn("BINDING_SCHEMA_DRIFT", self.page.locator("#query-outcome").inner_text())

    def test_timeout_and_expired_are_not_empty_results(self):
        self.save()
        self.query(fault="timeout")
        self.assertIn("SOURCE_TIMEOUT", self.page.locator("#query-outcome").inner_text())
        self.assertIsNone(self.model()["snapshot"], "failed execution must not claim a published result snapshot")
        self.assertTrue(self.page.locator("#result-box").is_hidden())
        self.query(fault="expired")
        self.assertIn("RESULT_EXPIRED", self.page.locator("#query-outcome").inner_text())
        self.assertTrue(self.page.locator("#result-box").is_hidden())

    def test_bounded_root_set_respects_direction_and_partial_scope(self):
        self.save("orphans")
        self.page.locator("#tab-query").click()
        self.page.locator("#query-root-mode").select_option("bounded")
        self.assertTrue(self.page.locator("#query-root").is_disabled())
        self.page.locator("#run-query").click()
        self.assertIn("EQ-001、EQ-002", self.page.locator("#query-outcome").inner_text())
        self.assertIn("COMPLETE", self.model()["qualityResult"])
        self.assertIn("LOT-11", self.page.locator("#result-rows").inner_text())
        self.page.locator("#query-direction").select_option("IN")
        self.page.locator("#run-query").click()
        self.assertIn("LOT-11、LOT-14", self.page.locator("#query-outcome").inner_text())
        self.assertIn("PARTIAL / KNOWN_SUBSET", self.model()["qualityResult"])
        self.assertIn("EQ-001", self.page.locator("#result-rows").inner_text())
        self.assertIn("已知子集 1 条", self.page.locator("#query-outcome").inner_text())

    def test_valid_fixture_does_not_offer_unverified_orphan(self):
        self.save("normal")
        self.page.locator("#tab-query").click()
        self.page.locator("#query-direction").select_option("IN")
        self.assertEqual([], self.page.locator('#query-root option[value="LOT-14"]').all())
        self.page.locator("#query-root-mode").select_option("bounded")
        self.page.locator("#run-query").click()
        self.assertIn("LOT-11、LOT-13", self.page.locator("#query-outcome").inner_text())
        self.assertIn("COMPLETE", self.model()["qualityResult"])

    def test_unsaved_navigation_guard_cancel_and_confirm(self):
        self.page.locator("#fk-column").select_option("order_id")
        self.assertTrue(self.model()["dirty"])
        self.page.locator("#nav-query").click()
        self.assertEqual("config", self.model()["view"])
        self.assertTrue(self.page.locator("#unsaved-modal").is_visible())
        self.page.locator("#discard-cancel").click()
        self.assertFalse(self.page.locator("#unsaved-modal").is_visible())
        self.assertTrue(self.model()["dirty"])
        self.page.locator("#nav-query").click()
        self.page.locator("#discard-confirm").click()
        self.assertEqual("query", self.model()["view"])
        self.assertFalse(self.model()["dirty"])

    def test_discard_unsaved_restores_last_saved_revision_choice(self):
        self.save()
        self.page.locator("#tab-config").click()
        self.page.locator("#target-binding").select_option("B-15:r2")
        self.assertTrue(self.model()["dirty"])
        self.page.locator("#nav-query").click()
        self.page.locator("#discard-confirm").click()
        self.page.locator("#nav-config").click()
        self.assertEqual("B-15:r3", self.page.locator("#target-binding").input_value())
        self.assertFalse(self.model()["dirty"])

    def test_draft_saved_revision_and_validation_qualification_are_separate(self):
        self.assertIn("尚无 LinkBinding", self.page.locator("#persist-pill").inner_text())
        self.page.locator("#target-binding").select_option("B-15:r2")
        self.assertIn("编辑中", self.page.locator("#draft-pill").inner_text())
        self.assertIn("尚无 LinkBinding", self.page.locator("#persist-pill").inner_text())
        self.page.locator("#config-undo").click()
        self.save()
        self.assertIn("LB-DEMO-021 · r1", self.page.locator("#persist-pill").inner_text())
        self.page.locator("#tab-config").click()
        self.page.locator("#target-binding").select_option("B-15:r2")
        self.assertIn("编辑中", self.page.locator("#draft-pill").inner_text())
        self.assertIn("LB-DEMO-021 · r1", self.page.locator("#persist-pill").inner_text())

    def test_cas_comparison_panel_preserves_draft_and_requires_revalidation(self):
        self.page.locator("#fk-column").select_option("equipment_code")
        self.save("cas")
        self.assertTrue(self.page.locator("#compare-cas").is_visible())
        self.page.locator("#resolve-cas").click()
        self.assertTrue(self.page.locator("#view-config").is_visible())
        self.assertFalse(self.model()["validated"])
        self.assertFalse(self.model()["saved"])
        self.assertTrue(self.page.locator("#save-binding").is_disabled())

    def test_late_validation_response_must_not_override_new_binding_choice(self):
        self.page.locator("#config-validate").click()
        self.page.locator("#simulate-late-validation").click()
        self.page.locator("#tab-config").click()
        self.page.locator("#target-binding").select_option("B-15:r2")
        self.page.wait_for_timeout(520)
        self.assertIn("已丢弃", self.page.locator("#async-state").inner_text())
        self.assertFalse(self.model()["validated"])
        self.assertTrue(self.page.locator("#continue-save").is_disabled())

    def test_late_page_response_must_not_restore_stale_objects(self):
        self.save()
        self.query()
        snap=self.model()["snapshot"]
        self.page.locator("#simulate-late-page").click()
        self.page.locator("#query-root").select_option("EQ-002")
        self.page.wait_for_timeout(520)
        self.assertIn("已丢弃", self.page.locator("#async-page-state").inner_text())
        self.assertTrue(self.page.locator("#result-box").is_hidden())
        self.assertEqual(snap,self.model()["snapshot"],"new root requires explicitly new execution")

    def test_source_permission_failure_masks_validation_source_details(self):
        self.validate("source")
        details = self.page.locator("#validation-source-description").inner_text()
        self.assertIn("隐藏", details)
        self.assertNotIn("inspection_lot", details)
        self.assertTrue(self.page.locator("#continue-save").is_disabled())

    def test_revoked_access_masks_sensitive_evidence_but_keeps_safe_execution_id(self):
        self.save()
        self.query()
        self.page.locator("#query-fault").select_option("revoke")
        self.page.locator("#next-page").click()
        self.page.locator("#tab-evidence").click()
        self.assertIn("execution-demo-", self.page.locator("#ev-exec").inner_text())
        self.assertIn("授权撤销", self.page.locator("#ev-sources").inner_text())
        self.assertNotIn("inspection_lot", self.page.locator("#ev-sources").inner_text())
        self.assertIn("没有查看", self.page.locator("#ev-version").inner_text())
        self.assertTrue(self.page.locator("#result-box").is_hidden())

    def test_draft_context_and_frozen_execution_versions_must_not_be_mixed(self):
        self.page.locator("#source-binding").select_option("B-11:r1")
        self.assertIn("B-11:r1", self.page.locator("#source-version-summary").inner_text())
        self.assertIn("尚无 LinkBinding", self.page.locator("#persist-pill").inner_text())
        self.page.locator("#config-validate").click()
        self.page.locator("#continue-save").click()
        self.page.locator("#save-binding").click()
        self.query()
        self.page.locator("#tab-evidence").click()
        self.assertIn("B-11:r1", self.page.locator("#ev-version").inner_text())
        self.page.locator("#tab-config").click()
        self.page.locator("#source-binding").select_option("B-11:r2")
        self.assertIn("B-11:r2", self.page.locator("#source-version-summary").inner_text())
        self.assertIn("LB-DEMO-021 · r1", self.page.locator("#persist-pill").inner_text())
        self.page.locator("#tab-evidence").click()
        self.page.locator("#discard-confirm").click()
        self.assertIn("B-11:r1", self.page.locator("#ev-version").inner_text())

    def test_narrow_viewport_has_no_horizontal_overflow(self):
        self.page.set_viewport_size({"width": 375, "height": 812})
        self.assertTrue(self.page.locator("#view-config").is_visible())
        overflow = self.page.evaluate("document.documentElement.scrollWidth > window.innerWidth + 2")
        self.assertFalse(overflow, "low-fi prototype must remain usable on a narrow viewport")


if __name__ == "__main__":
    unittest.main(verbosity=2)
