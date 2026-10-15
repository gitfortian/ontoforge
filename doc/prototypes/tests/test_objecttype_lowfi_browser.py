"""Real headless-Chromium checks for OFIP-0002 discussion-only HTML prototype.

Only in-browser mock behaviour is tested. These tests DO NOT exercise Java API,
PostgreSQL persistence, Native compiler or production authentication.
"""
from __future__ import annotations

import os
import unittest
from pathlib import Path

from playwright.sync_api import sync_playwright

PAGE = Path(__file__).resolve().parents[1] / "ofip-0002-objecttype-lowfi.html"


class LowFiBrowserUX(unittest.TestCase):
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
        self.console_errors = []
        self.page.on("pageerror", lambda error: self.console_errors.append(str(error)))
        self.page.goto(PAGE.as_uri())
        self.page.get_by_role("heading", name="模型包", exact=True).wait_for()

    def tearDown(self):
        if os.environ.get("UX_CAPTURE_SCREENSHOTS"):
            target = Path(os.environ.get("UX_SCREENSHOT_DIR", "/tmp/ontoforge-lowfi-shots"))
            target.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(target / (self.__class__.__name__ + "__" + self._testMethodName + ".png")), full_page=True)
        self.assertEqual(self.console_errors, [], "Prototype JavaScript exception")
        self.page.close()

    def create_package(self):
        self.page.get_by_role("button", name="新建第一个模型包").click()
        self.page.locator("#package-id").fill("manufacturing.production")
        self.page.get_by_role("button", name="创建空草稿并进入").click()
        self.page.get_by_role("heading", name="manufacturing.production").wait_for()
        self.assertIn("revision 1", self.page.locator("#view").inner_text())

    def edit_first_type(self):
        self.page.get_by_role("button", name="新建首个对象类型").first.click()
        self.page.locator("#obj-id").fill("production-batch")
        self.page.locator("#obj-api").fill("ProductionBatch")
        self.page.locator("#obj-label").fill("生产批次")

    def add_primary_key(self):
        self.page.get_by_role("button", name="＋ 添加第一个属性").click()
        self.page.locator("#p0-id").fill("batch-id")
        self.page.locator("#p0-api").fill("batchId")
        self.page.locator("#p0-label").fill("批次编号")
        self.page.locator("#p0-required").check()
        self.page.locator("#primary-ref").select_option("batch-id")

    def save(self):
        self.page.get_by_role("button", name="保存编辑进度（模拟）").click()

    def test_ux1_ux3_empty_package_wip_save_and_reopen(self):
        self.page.get_by_text("暂无模型包草稿").wait_for()
        self.create_package()
        self.edit_first_type()
        self.save()
        self.page.get_by_role("button", name="返回模型包").click()
        self.page.get_by_role("button", name="继续编辑").first.click()
        self.assertEqual(self.page.locator("#obj-id").input_value(), "production-batch")
        self.assertEqual(self.page.locator("#obj-api").input_value(), "ProductionBatch")
        self.assertEqual(self.page.locator("#primary-ref").input_value(), "")
        self.page.get_by_role("button", name="检查模型完整性").click()
        self.assertIn("未完成", self.page.locator("#flash").inner_text())
        self.assertIn("primaryKeyRef", self.page.locator("#view").inner_text())

    def test_ux5_ux6_complete_valid_edit_invalidates_validation(self):
        self.create_package()
        self.edit_first_type()
        self.add_primary_key()
        self.save()
        self.page.get_by_role("button", name="检查模型完整性").click()
        self.assertIn("校验通过", self.page.locator("#flash").inner_text())
        first_rev = self.page.evaluate("state.records[0].revision")
        self.assertEqual(self.page.evaluate("state.records[0].validation.status"), "VALID")
        self.page.locator("#obj-desc").fill("新版业务定义")
        self.save()
        self.assertEqual(self.page.evaluate("state.records[0].revision"), first_rev + 1)
        self.assertIsNone(self.page.evaluate("state.records[0].validation"))
        self.assertIn("未校验", self.page.locator("#view").inner_text())

    def test_ux4_missing_and_duplicate_attributes(self):
        self.create_package()
        self.edit_first_type()
        self.save()
        self.page.get_by_role("button", name="检查模型完整性").click()
        self.assertEqual(self.page.evaluate("state.records[0].validation.status"), "INCOMPLETE")
        self.add_primary_key()
        self.page.get_by_role("button", name="＋ 添加属性").click()
        self.page.locator("#p1-id").fill("batch-id")
        self.page.locator("#p1-api").fill("batchId")
        self.save()
        self.page.get_by_role("button", name="检查模型完整性").click()
        self.assertEqual(self.page.evaluate("state.records[0].validation.status"), "INVALID")
        self.assertIn("DUPLICATE_ID", self.page.locator("#view").inner_text())

    def test_ux7_cancel_navigate_does_not_discard_unsaved(self):
        self.create_package()
        self.edit_first_type()
        self.page.locator("#obj-desc").fill("千万不要丢失")
        self.page.once("dialog", lambda dialog: dialog.dismiss())
        self.page.get_by_role("button", name="返回模型包").click()
        self.assertEqual(self.page.locator("#obj-desc").input_value(), "千万不要丢失")
        self.page.once("dialog", lambda dialog: dialog.accept())
        self.page.get_by_role("button", name="返回模型包").click()
        self.assertIn("manufacturing.production", self.page.locator("#view").inner_text())

    def test_ux8_revision_conflict_keeps_user_input(self):
        self.create_package()
        self.edit_first_type()
        self.save()
        self.page.locator("#obj-desc").fill("本地重要编辑")
        self.page.get_by_role("button", name="模拟另一标签页保存").click()
        self.save()
        self.assertIn("DRAFT_REVISION_CONFLICT", self.page.locator("#flash").inner_text())
        self.assertEqual(self.page.locator("#obj-desc").input_value(), "本地重要编辑")
        self.assertIn("载入最新", self.page.locator("#view").inner_text())

    def test_ux9_offline_re_render_must_preserve_unsaved_input(self):
        """Regression: switching the mock network state must not erase unsaved typing."""
        self.create_package()
        self.edit_first_type()
        self.save()
        self.page.locator("#obj-desc").fill("断网前新写的内容")
        self.page.get_by_role("button", name="模拟断网").click()
        self.assertEqual(self.page.locator("#obj-desc").input_value(), "断网前新写的内容")
        self.save()
        self.assertIn("未确认", self.page.locator("#flash").inner_text())
        self.assertEqual(self.page.locator("#obj-desc").input_value(), "断网前新写的内容")
        self.page.get_by_role("button", name="恢复模拟网络").click()
        self.save()
        self.assertEqual(
            self.page.evaluate("state.records[0].objectTypes[0].description"),
            "断网前新写的内容",
        )

    def test_conflicting_package_id_not_silently_overwritten(self):
        self.create_package()
        self.page.locator("#nav-pkg").click()
        self.page.get_by_role("button", name="＋ 创建模型包").click()
        self.page.locator("#package-id").fill("manufacturing.production")
        self.page.get_by_role("button", name="创建空草稿并进入").click()
        self.assertIn("PACKAGE_DRAFT_EXISTS", self.page.locator("#flash").inner_text())
        self.assertEqual(self.page.evaluate("state.records.length"), 1)

    def test_unhandled_link_and_multiple_types_are_never_silently_lost(self):
        self.create_package()
        self.edit_first_type()
        self.save()
        self.page.evaluate("state.records[0].links = [{id:'future-link'}]")
        self.page.locator("#obj-desc").fill("保留其他定义")
        self.save()
        self.assertIn("阻止保存", self.page.locator("#flash").inner_text())
        self.assertEqual(self.page.evaluate("state.records[0].links.length"), 1)

    def test_refresh_resets_mock_no_fake_persistence(self):
        self.create_package()
        self.page.reload()
        self.page.get_by_text("暂无模型包草稿").wait_for()
        self.assertEqual(self.page.evaluate("state.records.length"), 0)
        self.assertIn("不连接 API / PostgreSQL", self.page.locator("body").inner_text())

    def test_narrow_viewport_shows_real_task_controls(self):
        self.page.set_viewport_size({"width": 390, "height": 844})
        self.page.get_by_role("button", name="新建第一个模型包").click()
        self.assertTrue(self.page.locator("#package-id").is_visible())


if __name__ == "__main__":
    unittest.main(verbosity=2)
