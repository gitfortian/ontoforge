"""OFIP-0003 UX-Gate-Low REAL Chromium review of a browser-memory mock.

This suite DOES NOT claim existence of V3, Snapshot APIs, SHA-256/JCS,
production approval/release, PostgreSQL persistence or enterprise auth.
"""
from __future__ import annotations

import os
import unittest
from pathlib import Path
from playwright.sync_api import sync_playwright

PAGE = Path(__file__).resolve().parents[1] / "ofip-0003-snapshot-lowfi.html"


class SnapshotLowFiProductReview(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pw = sync_playwright().start()
        cls.browser = cls.pw.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.pw.stop()

    def setUp(self):
        self.page = self.browser.new_page(viewport={"width": 1365, "height": 900}, locale="zh-CN")
        self.errors = []
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))
        self.page.goto(PAGE.as_uri())
        self.page.get_by_role("heading", name="模型包与快照", exact=True).wait_for()

    def tearDown(self):
        if os.getenv("UX_CAPTURE_SCREENSHOTS"):
            root = Path(os.getenv("UX_SCREENSHOT_DIR", "/tmp/ontoforge-ux-shots"))
            root.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(
                path=str(root / (self.__class__.__name__ + "__" + self._testMethodName + ".png")),
                full_page=True)
        self.assertEqual(self.errors, [], "Uncaught prototype JavaScript error")
        self.page.close()

    def create(self):
        self.page.get_by_role("button", name="创建示例模型包（模拟）").click()
        self.page.get_by_role("heading", name="manufacturing.production / 当前草稿").wait_for()

    def sample(self):
        self.page.get_by_role("button", name="使用生产批次示例填充").click()
        self.assertEqual(self.page.locator("#obj-id").input_value(), "production-batch")

    def save(self):
        self.page.get_by_role("button", name="保存编辑进度").click()
        self.assertIn("保存完成", self.page.locator("#flash").inner_text())

    def validate(self):
        self.page.get_by_role("button", name="检查模型完整性").click()

    def valid_draft(self):
        self.create()
        self.sample()
        self.save()
        self.validate()
        self.assertIn("VALID", self.page.locator("#flash").inner_text())

    def freeze(self):
        self.page.get_by_role("button", name="冻结当前修订为快照（模拟）").click()

    def test_ux01_empty_model_package_and_wip_save(self):
        self.assertIn("还没有模型包草稿", self.page.locator("#view").inner_text())
        self.create()
        self.page.locator("#obj-id").fill("production-batch")
        self.save()
        self.assertIn("revision 2", self.page.locator("#view").inner_text())
        self.validate()
        self.assertIn("INCOMPLETE", self.page.locator("#flash").inner_text())
        self.assertTrue(self.page.locator("#freeze").is_disabled())

    def test_ux02_validation_error_jumps_to_field(self):
        self.create()
        self.page.locator("#obj-id").fill("production-batch")
        self.save()
        self.validate()
        self.page.locator(".issue").filter(has_text="/objectTypes/0/apiName").click()
        self.assertEqual(self.page.evaluate("document.activeElement.id"), "obj-api")
        self.assertTrue(self.page.locator("#freeze").is_disabled())

    def test_ux03_unsaved_input_blocks_validation_and_freeze(self):
        self.valid_draft()
        self.page.locator("#obj-desc").fill("未保存更新")
        self.assertTrue(self.page.locator("#validate").is_disabled())
        self.assertTrue(self.page.locator("#freeze").is_disabled())
        self.assertIn("尚未保存", self.page.locator("#current-status").inner_text())
        self.save()
        self.assertTrue(self.page.locator("#freeze").is_disabled())
        self.assertIn("NOT_RUN", self.page.locator("#view").inner_text())

    def test_ux04_validated_revision_freezes_read_only_snapshot(self):
        self.valid_draft()
        self.assertTrue(self.page.locator("#freeze").is_enabled())
        self.freeze()
        self.assertEqual(self.page.evaluate("state.snapshots.length"), 1)
        self.assertTrue(self.page.locator("#freeze").is_disabled())
        self.page.get_by_role("button", name="历史快照").click()
        self.page.get_by_role("button", name="查看只读内容").click()
        self.assertIn("READ ONLY", self.page.locator("#view").inner_text())
        self.assertEqual(self.page.locator("input").count(), 0)
        self.assertIn("非 SHA-256", self.page.locator("#view").inner_text())
        self.assertIn("未发布", self.page.locator("#view").inner_text())

    def test_ux05_two_immutable_versions_and_semantic_diff(self):
        self.valid_draft()
        self.freeze()
        self.page.locator("#prop-searchable").uncheck()
        self.page.locator("#obj-desc").fill("更新批次定义")
        self.save()
        self.validate()
        self.freeze()
        self.assertEqual(self.page.evaluate("state.snapshots.length"), 2)
        self.assertEqual(self.page.evaluate("state.snapshots[0].doc.property.searchable"), True)
        self.assertEqual(self.page.evaluate("state.snapshots[1].doc.property.searchable"), False)
        self.page.get_by_role("button", name="历史快照").click()
        self.page.get_by_role("button", name="比较最近两份快照").click()
        rows = self.page.locator("#diff-table").inner_text()
        self.assertIn("Property.searchable", rows)
        self.assertIn("POTENTIALLY_BREAKING", rows)
        self.assertIn("DOC_ONLY", rows)
        self.assertIn("不能声称兼容性通过", self.page.locator("#view").inner_text())

    def test_ux06_same_idempotency_key_no_duplicate_snapshot(self):
        self.valid_draft()
        self.freeze()
        self.page.get_by_role("button", name="历史快照").click()
        self.page.get_by_role("button", name="模拟同一幂等键重试").click()
        self.assertIn("HTTP 200 同键重放", self.page.locator("#flash").inner_text())
        self.assertEqual(self.page.evaluate("state.snapshots.length"), 1)

    def test_ux07_unknown_commit_result_recover_by_original_key(self):
        self.valid_draft()
        self.page.locator("#lost-reply").check()
        self.freeze()
        self.assertIn("RESULT_UNKNOWN", self.page.locator("#flash").inner_text())
        self.assertTrue(self.page.locator("#freeze").is_disabled())
        self.assertEqual(self.page.evaluate("state.snapshots.length"), 1)
        original = self.page.evaluate("state.pendingKey")
        self.page.get_by_role("button", name="按幂等键核对冻结结果").click()
        self.assertEqual(self.page.evaluate("state.snapshots.length"), 1)
        self.assertEqual(self.page.evaluate("state.lastKey"), original)
        self.assertIsNone(self.page.evaluate("state.pendingKey"))
        self.assertIn("按幂等键找回", self.page.locator("#flash").inner_text())

    def test_ux08_concurrent_save_cannot_freeze_old_revision(self):
        self.valid_draft()
        self.page.get_by_role("button", name="模拟另一标签页保存").click()
        self.assertEqual(self.page.evaluate("state.serverRev"), 3)
        self.freeze()
        self.assertIn("DRAFT_REVISION_CONFLICT", self.page.locator("#flash").inner_text())
        self.assertEqual(self.page.evaluate("state.snapshots.length"), 0)
        self.page.get_by_role("button", name="核对服务器 revision").click()
        self.assertIn("服务器 revision 3", self.page.locator("#compare-revision").inner_text())
        self.page.once("dialog", lambda dialog: dialog.dismiss())
        self.page.get_by_role("button", name="确认放弃本地并载入服务器版").click()
        self.assertEqual(self.page.evaluate("state.draft.revision"), 2)
        self.page.once("dialog", lambda dialog: dialog.accept())
        self.page.get_by_role("button", name="确认放弃本地并载入服务器版").click()
        self.assertEqual(self.page.evaluate("state.draft.revision"), 3)
        self.assertIn("NOT_RUN", self.page.locator("#view").inner_text())

    def test_ux09_unknown_assets_fail_closed(self):
        self.valid_draft()
        self.page.locator("#unsupported").check()
        self.freeze()
        self.assertIn("UNSUPPORTED_DEFINITION", self.page.locator("#flash").inner_text())
        self.assertEqual(self.page.evaluate("state.snapshots.length"), 0)

    def test_ux10_unsaved_leave_requires_explicit_confirmation(self):
        self.create()
        self.page.locator("#obj-desc").fill("本地修改")
        self.page.once("dialog", lambda dialog: dialog.dismiss())
        self.page.get_by_role("button", name="模型包").first.click()
        self.assertEqual(self.page.locator("#obj-desc").input_value(), "本地修改")
        self.page.once("dialog", lambda dialog: dialog.accept())
        self.page.get_by_role("button", name="模型包").first.click()
        self.page.get_by_role("heading", name="模型包与快照", exact=True).wait_for()
        self.assertFalse(self.page.evaluate("state.dirty"))

    def test_ux11_snapshot_immutability_after_draft_edit(self):
        self.valid_draft()
        self.freeze()
        old = self.page.evaluate("JSON.stringify(state.snapshots[0].doc)")
        self.page.locator("#obj-desc").fill("新草稿描述")
        self.save()
        self.assertEqual(self.page.evaluate("JSON.stringify(state.snapshots[0].doc)"), old)
        self.page.get_by_role("button", name="历史快照").click()
        self.page.get_by_role("button", name="查看只读内容").click()
        self.assertIn("生产批次", self.page.locator("#snapshot-content").inner_text())
        self.assertNotIn("新草稿描述", self.page.locator("#snapshot-content").inner_text())

    def test_ux12_mobile_no_horizontal_scrolling_on_editor_and_history(self):
        self.page.set_viewport_size({"width": 390, "height": 844})
        self.valid_draft()
        self.freeze()
        self.assertLessEqual(self.page.evaluate("document.documentElement.scrollWidth"), 392)
        self.page.get_by_role("button", name="历史快照").click()
        self.assertLessEqual(self.page.evaluate("document.documentElement.scrollWidth"), 392)
        self.assertTrue(self.page.get_by_role("heading", name="历史模型快照").is_visible())

    def test_ux13_refresh_clears_only_mock_data_without_persistence_claim(self):
        self.valid_draft()
        self.freeze()
        self.page.reload()
        self.assertIsNone(self.page.evaluate("state.draft"))
        self.assertEqual(self.page.evaluate("state.snapshots.length"), 0)
        self.assertIn("刷新即清空", self.page.locator("footer").inner_text())


if __name__ == "__main__":
    unittest.main(verbosity=2)
