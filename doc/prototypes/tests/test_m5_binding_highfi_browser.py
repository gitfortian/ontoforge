"""Real Chromium UX checks for the M5 high-fidelity Data Binding prototype.

These tests validate mocked prototype interactions only. They do not exercise Spring Boot,
PostgreSQL, source credentials, or persisted production Binding records.
"""
from __future__ import annotations

import os
import unittest
from pathlib import Path

from playwright.sync_api import sync_playwright

PAGE = Path(__file__).resolve().parents[1] / "m5-data-binding-highfi.html"


class M5DataBindingHighFiBrowser(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.page = self.browser.new_page(viewport={"width": 1440, "height": 960}, locale="zh-CN")
        self.console_errors: list[str] = []
        self.page.on("pageerror", lambda error: self.console_errors.append(str(error)))
        self.page.goto(PAGE.as_uri())
        self.page.get_by_role("heading", name="数据绑定", exact=True).wait_for()

    def tearDown(self):
        if os.environ.get("UX_CAPTURE_SCREENSHOTS"):
            target = Path(os.environ.get("UX_SCREENSHOT_DIR", "/tmp/ontoforge-m5-highfi-shots"))
            target.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(
                path=str(target / (self.__class__.__name__ + "__" + self._testMethodName + ".png")),
                full_page=True,
            )
        self.assertEqual(self.console_errors, [], "M5 high-fi prototype JavaScript exception")
        self.page.close()

    def start_to_mapping(self):
        self.page.locator("#createBinding").click()
        self.page.locator("#to2").click()
        self.page.locator("#to3").click()
        self.page.get_by_role("heading", name="配置来源键与属性映射").wait_for()

    def start_to_preview(self):
        self.start_to_mapping()
        self.page.locator("#to4").click()
        self.page.get_by_role("heading", name="预览映射结果并运行有界校验").wait_for()

    def preview_and_validate(self):
        self.page.locator("#readPreview").click()
        self.page.locator("#runValidation").click()

    def save_current_binding(self):
        self.start_to_preview()
        self.preview_and_validate()
        self.page.locator("#to5").click()
        self.page.locator("#saveBinding").click()
        self.assertIn("绑定版本已保存", self.page.locator("#saveState").inner_text())
        self.assertTrue(self.page.locator("#saveBinding").is_disabled())

    def test_empty_state_create_preview_save_reopen_detail(self):
        self.assertTrue(self.page.locator("#emptyState").is_visible())
        self.assertEqual(self.page.locator("#metricBindings").inner_text(), "0")
        self.save_current_binding()
        self.page.locator("#openSaved").click()
        self.assertTrue(self.page.locator("#detailView").is_visible())
        self.assertIn("revision 1", self.page.locator("#detailRevision").inner_text())
        self.assertEqual(self.page.locator("#detailMappings tr").count(), 4)
        self.page.locator("#detailBack").click()
        self.assertTrue(self.page.locator("#bindingTableWrap").is_visible())
        self.assertEqual(self.page.locator("#bindingRows tr").count(), 1)
        self.assertEqual(self.page.locator("#metricBindings").inner_text(), "1")
        self.assertEqual(self.page.locator("#metricValidated").inner_text(), "1")

    def test_existing_type_binding_edits_new_revision_without_duplicate(self):
        self.save_current_binding()
        self.page.locator("#returnList").click()
        self.page.locator('[data-edit="binding-supplier-01"]').click()
        self.assertTrue(self.page.locator("#existingBindingNotice").is_visible())
        self.assertIn("revision 1", self.page.locator("#existingBindingText").inner_text())
        self.page.locator("#to2").click()
        self.page.locator("#to3").click()
        self.page.locator("#to4").click()
        self.preview_and_validate()
        self.page.locator("#to5").click()
        self.page.locator("#saveBinding").click()
        self.assertIn("revision 2", self.page.locator("#saveState").inner_text())
        self.page.locator("#returnList").click()
        self.assertEqual(self.page.locator("#bindingRows tr").count(), 1)
        self.assertIn("revision 2", self.page.locator("#bindingRows").inner_text())
        self.assertEqual(self.page.locator("#metricBindings").inner_text(), "1")

    def test_primary_key_and_property_type_mismatches_are_rejected(self):
        self.start_to_mapping()
        self.page.locator("#mapPk").select_option("rating")
        self.page.locator("#to4").click()
        self.assertIn("UNSUPPORTED_MAPPING_TYPE", self.page.locator("#mappingMessage").inner_text())
        self.page.locator("#mapPk").select_option("vendor_code")
        self.page.locator("#mapRating").select_option("vendor_code")
        self.page.locator("#to4").click()
        self.assertIn("属性类型不兼容", self.page.locator("#mappingMessage").inner_text())
        self.assertTrue(self.page.locator("#step3").is_visible())

    def test_minimum_two_non_key_attributes_is_enforced(self):
        self.start_to_mapping()
        self.page.locator("#mapName").select_option("")
        self.page.locator("#mapActive").select_option("")
        self.page.locator("#to4").click()
        self.assertIn("属性映射不足", self.page.locator("#mappingMessage").inner_text())
        self.assertTrue(self.page.locator("#step3").is_visible())

    def test_mapped_object_preview_reflects_selected_mapping(self):
        self.start_to_preview()
        self.preview_and_validate()
        self.assertTrue(self.page.locator("#previewTables").is_visible())
        self.assertIn("supplierCode ← vendor_code", self.page.locator("#mappedPreviewHead").inner_text())
        self.assertEqual(
            self.page.locator("#mappedPreviewRows tr").first.locator("td").all_inner_texts(),
            ["V-1001", "北辰精密", "4.70", "true"],
        )
        self.assertIn("有界校验通过", self.page.locator("#validationResult").inner_text())

    def test_source_drift_has_explicit_rediscovery_recovery(self):
        self.start_to_preview()
        self.page.locator("#demoScenario").select_option("drift")
        self.page.locator("#readPreview").click()
        self.assertIn("BINDING_SCHEMA_DRIFT", self.page.locator("#previewState").inner_text())
        self.assertTrue(self.page.locator("#backToSource").is_visible())
        self.page.locator("#backToSource").click()
        self.page.locator("#rediscoverSource").click()
        self.assertIn("来源结构已重新发现", self.page.locator("#sourceDiscoveryState").inner_text())
        self.page.locator("#to3").click()
        self.page.locator("#to4").click()
        self.preview_and_validate()
        self.assertIn("有界校验通过", self.page.locator("#validationResult").inner_text())

    def test_source_unavailable_never_looks_like_empty_success(self):
        self.start_to_preview()
        self.page.locator("#demoScenario").select_option("unavailable")
        self.page.locator("#readPreview").click()
        self.assertIn("SOURCE_UNAVAILABLE", self.page.locator("#previewState").inner_text())
        self.assertFalse(self.page.locator("#previewTables").is_visible())
        self.assertTrue(self.page.locator("#backToSource").is_visible())

    def test_identity_conflict_and_budget_exhaustion_block_save(self):
        self.start_to_preview()
        for scenario, code in (("duplicate", "IDENTITY_CONFLICT"), ("budget", "QUERY_BUDGET_EXCEEDED")):
            with self.subTest(scenario=scenario):
                self.page.locator("#demoScenario").select_option(scenario)
                self.preview_and_validate()
                self.assertIn(code, self.page.locator("#validationResult").inner_text())
                self.assertTrue(self.page.locator("#to5").is_disabled())
                self.assertTrue(self.page.locator("#saveBinding").is_disabled())
        self.assertTrue(self.page.locator("#backToSource").is_visible())

    def test_compare_and_swap_conflict_recovers_and_saves_next_revision(self):
        self.start_to_preview()
        self.page.locator("#demoScenario").select_option("cas")
        self.preview_and_validate()
        self.page.locator("#to5").click()
        self.page.locator("#saveBinding").click()
        self.assertIn("BINDING_REVISION_CONFLICT", self.page.locator("#saveState").inner_text())
        self.assertTrue(self.page.locator("#casDiff").is_visible())
        self.assertEqual(self.page.locator("#casDiffRows tr").count(), 4)
        self.page.locator("#loadLatest").click()
        self.assertIn("已载入最新版本", self.page.locator("#saveState").inner_text())
        self.page.locator("#readPreview").click()
        self.page.locator("#runValidation").click()
        self.page.locator("#to5").click()
        self.page.locator("#saveBinding").click()
        self.assertIn("revision 2", self.page.locator("#saveState").inner_text())
        self.assertEqual(self.page.locator("#bindingRows tr").count(), 1)

    def test_identity_domain_changes_when_source_key_semantics_change(self):
        self.start_to_mapping()
        self.page.locator("#mapPk").select_option("name")
        self.assertEqual(self.page.locator("#identityDomainPreview").inner_text(), "supplier-name-domain")
        self.page.locator("#mapPk").select_option("vendor_code")
        self.assertEqual(self.page.locator("#identityDomainPreview").inner_text(), "supplier-vendor-code-domain")

    def test_cancel_prompts_before_discarding_unsaved_mapping(self):
        self.start_to_mapping()
        self.page.locator("#mapPk").select_option("name")
        self.page.once("dialog", lambda dialog: dialog.dismiss())
        self.page.locator("#cancelWizard").click()
        self.assertTrue(self.page.locator("#wizardView").is_visible())
        self.page.once("dialog", lambda dialog: dialog.accept())
        self.page.locator("#cancelWizard").click()
        self.assertTrue(self.page.locator("#listView").is_visible())
        self.assertEqual(self.page.locator("#bindingRows tr").count(), 0)

    def test_narrow_viewport_keeps_source_controls_operable(self):
        self.page.set_viewport_size({"width": 390, "height": 844})
        self.page.locator("#createBinding").click()
        self.page.locator("#to2").click()
        self.assertTrue(self.page.locator("#sourceTable").is_visible())
        self.assertTrue(self.page.locator("#to3").is_visible())


if __name__ == "__main__":
    unittest.main(verbosity=2)
