"""Real Chromium interaction checks for the M5 Data Binding UX-Low candidate.

These tests cover only the static prototype's mocked state transitions. They do not
exercise the Spring Boot API, PostgreSQL, authentication, or persisted Binding records.
"""
from __future__ import annotations

import os
import unittest
from pathlib import Path

from playwright.sync_api import sync_playwright

PAGE = Path(__file__).resolve().parents[1] / "m5-data-binding-lowfi.html"


class M5DataBindingLowFiBrowser(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.page = self.browser.new_page(viewport={"width": 1365, "height": 900}, locale="zh-CN")
        self.console_errors: list[str] = []
        self.page.on("pageerror", lambda error: self.console_errors.append(str(error)))
        self.page.goto(PAGE.as_uri())
        self.page.get_by_role("heading", name="数据绑定", exact=True).wait_for()

    def tearDown(self):
        if os.environ.get("UX_CAPTURE_SCREENSHOTS"):
            target = Path(os.environ.get("UX_SCREENSHOT_DIR", "/tmp/ontoforge-lowfi-shots"))
            target.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(
                path=str(target / (self.__class__.__name__ + "__" + self._testMethodName + ".png")),
                full_page=True,
            )
        self.assertEqual(self.console_errors, [], "M5 prototype JavaScript exception")
        self.page.close()

    def to_mapping(self):
        self.page.get_by_role("button", name="继续：选择来源").click()
        self.page.get_by_role("heading", name="2. 选择数据来源").wait_for()
        self.page.get_by_role("button", name="确认来源并配置映射").click()
        self.page.get_by_role("heading", name="3. 配置主键与属性映射").wait_for()

    def to_preview(self):
        self.to_mapping()
        self.page.get_by_role("button", name="预览映射").click()
        self.page.get_by_role("heading", name="4. 预览与校验").wait_for()

    def preview_and_validate(self):
        self.page.get_by_role("button", name="读取样本预览").click()
        self.page.get_by_role("button", name="运行全量有界校验").click()

    def test_happy_path_mapping_validation_save_and_reopen(self):
        self.to_preview()
        self.preview_and_validate()
        self.assertIn("校验通过", self.page.locator("#validationResult").inner_text())
        self.assertIn("248 行", self.page.locator("#validationResult").inner_text())
        self.page.get_by_role("button", name="继续：保存绑定").click()
        self.page.get_by_role("heading", name="5. 保存并重新打开").wait_for()
        self.page.get_by_role("button", name="保存绑定").click()
        self.assertIn("已保存", self.page.locator("#saveState").inner_text())
        self.assertEqual(self.page.locator("#savedRevision").inner_text(), "revision 1")
        self.page.get_by_role("button", name="重新打开已保存版本").click()
        self.assertIn("重新打开成功", self.page.locator("#reopenState").inner_text())

    def test_duplicate_source_column_is_rejected_before_preview(self):
        self.to_mapping()
        self.page.locator("#mapName").select_option("vendor_code")
        self.page.get_by_role("button", name="预览映射").click()
        self.assertIn("来源列重复使用", self.page.locator("#mappingMessage").inner_text())
        self.assertTrue(self.page.locator("#step3").is_visible())

    def test_source_unavailable_does_not_look_like_empty_success(self):
        self.to_preview()
        self.page.locator("#scenario").select_option("unavailable")
        self.page.get_by_role("button", name="读取样本预览").click()
        self.assertIn("SOURCE_UNAVAILABLE", self.page.locator("#previewState").inner_text())
        self.assertFalse(self.page.locator("#previewTableWrap").is_visible())
        self.assertTrue(self.page.locator("#runValidation").is_disabled())

    def test_source_drift_requires_recheck_and_blocks_progress(self):
        self.to_preview()
        self.page.locator("#scenario").select_option("drift")
        self.page.get_by_role("button", name="读取样本预览").click()
        self.assertIn("BINDING_SCHEMA_DRIFT", self.page.locator("#previewState").inner_text())
        self.assertFalse(self.page.locator("#previewTableWrap").is_visible())
        self.assertTrue(self.page.locator("#runValidation").is_disabled())

    def test_duplicate_identity_and_budget_exhaustion_cannot_be_saved(self):
        for scenario, expected in (
            ("duplicate", "IDENTITY_CONFLICT"),
            ("budget", "QUERY_BUDGET_EXCEEDED"),
        ):
            with self.subTest(scenario=scenario):
                self.to_preview()
                self.page.locator("#scenario").select_option(scenario)
                self.preview_and_validate()
                self.assertIn(expected, self.page.locator("#validationResult").inner_text())
                self.assertTrue(self.page.locator("#to5").is_disabled())
                self.assertTrue(self.page.locator("#saveBinding").is_disabled())
                self.page.get_by_role("button", name="返回：映射").click()

    def test_compare_and_swap_conflict_keeps_binding_unsaved(self):
        self.to_preview()
        self.page.locator("#scenario").select_option("cas")
        self.preview_and_validate()
        self.page.get_by_role("button", name="继续：保存绑定").click()
        self.page.get_by_role("button", name="保存绑定").click()
        self.assertIn("BINDING_REVISION_CONFLICT", self.page.locator("#saveState").inner_text())
        self.assertEqual(self.page.locator("#savedId").inner_text(), "尚未创建")
        self.assertTrue(self.page.locator("#reopenBinding").is_disabled())

    def test_narrow_viewport_keeps_current_step_controls_visible(self):
        self.page.set_viewport_size({"width": 390, "height": 844})
        self.page.get_by_role("button", name="继续：选择来源").click()
        self.assertTrue(self.page.get_by_role("button", name="确认来源并配置映射").is_visible())
        self.assertTrue(self.page.locator("#sourceTable").is_visible())


if __name__ == "__main__":
    unittest.main(verbosity=2)
