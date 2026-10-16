"""OFIP-0004 UX-High: actual Chromium clicks against standalone memory-only mock.

No claims about Spring Boot API, PostgreSQL, Native compiler, authorization,
true snapshots/digests, industrial link facts, or official releases.
"""
from __future__ import annotations

import os
import unittest
from pathlib import Path
from playwright.sync_api import sync_playwright

PAGE = Path(__file__).resolve().parents[1] / "ofip-0004-linktype-highfi.html"


class LinkTypeHighFiBrowserReview(unittest.TestCase):
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
        self.page.on("pageerror", lambda err: self.errors.append(str(err)))
        self.page.goto(PAGE.as_uri())
        self.page.get_by_role("heading", name="manufacturing.production").wait_for()

    def tearDown(self):
        if os.getenv("UX_CAPTURE_SCREENSHOTS"):
            root = Path(os.getenv("UX_SCREENSHOT_DIR", "/tmp/ontoforge-ux-shots"))
            root.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(
                path=str(root / (self.__class__.__name__ + "__" + self._testMethodName + ".png")),
                full_page=True,
            )
        self.assertEqual([], self.errors, "No unhandled browser prototype script errors")
        self.page.close()

    def act(self, name):
        self.page.locator(".footer button[data-action='" + name + "']").click()
        if name == "freeze" and self.page.locator("#freezeDialog").is_visible():
            self.page.locator("button[data-action=confirmFreeze]").click()

    def seed(self):
        self.page.locator("button[data-action=seed]").click()
        self.assertEqual(3, self.page.evaluate("S.local.objectTypes.length"))
        self.assertEqual(2, self.page.evaluate("S.local.linkTypes.length"))

    def saved_valid(self):
        self.seed()
        self.act("save")
        self.act("validate")
        self.assertEqual("VALID", self.page.evaluate("S.validation.status"))

    def test_ux01_initial_product_shell_and_mock_boundary(self):
        self.assertIn("UX-HIGH", self.page.locator(".kicker").inner_text())
        self.assertIn("内存", self.page.locator(".endnote").inner_text())
        self.assertEqual(1, self.page.evaluate("S.local.objectTypes.length"))
        self.assertEqual(0, self.page.evaluate("S.local.linkTypes.length"))
        self.assertEqual(4, self.page.locator("nav.tabs button[role=tab]").count())

    def test_ux02_seeding_preserves_complete_relation_model_but_dirty(self):
        self.seed()
        self.assertTrue(self.page.evaluate("S.dirty"))
        self.assertIn("生产批次归属工单", self.page.locator("#view").inner_text())
        self.assertIn("多对一", self.page.locator("#view").inner_text())
        self.assertIn("未保存", self.page.locator("#footerState").inner_text())

    def test_ux03_full_save_validate_freeze_and_immutable_mock_history(self):
        self.saved_valid()
        self.act("freeze")
        self.assertEqual(1, self.page.evaluate("S.snapshots.length"))
        self.assertEqual(3, self.page.evaluate("S.snapshots[0].doc.objectTypes.length"))
        self.assertEqual(2, self.page.evaluate("S.snapshots[0].doc.linkTypes.length"))
        self.assertIn("非发布", self.page.locator("#view").inner_text())

    def test_ux04_relation_cardinality_change_produces_risk(self):
        self.saved_valid()
        self.act("freeze")
        self.page.locator("#tab-links").click()
        self.page.locator("select[data-kind=link][data-field=cardinality]").select_option("ONE_TO_ONE")
        self.act("save")
        self.act("validate")
        self.act("freeze")
        self.assertEqual(2, self.page.evaluate("S.snapshots.length"))
        self.page.locator("select[data-field=from]").select_option("1")
        self.page.locator("select[data-field=to]").select_option("2")
        self.assertIn("潜在破坏", self.page.locator("#view").inner_text())
        self.assertTrue(self.page.evaluate("S.snapshots[0].doc.linkTypes[0].cardinality==='MANY_TO_ONE'"))

    def test_ux05_full_professional_property_editor_and_enum_invalid_pk(self):
        self.seed()
        self.page.locator("#tab-objects").click()
        self.assertEqual(1, self.page.locator("select[data-kind=prop][data-field=classification]").count())
        self.assertEqual(1, self.page.locator("select[data-kind=prop][data-field=required]").count())
        self.assertEqual(1, self.page.locator("select[data-kind=prop][data-field=searchable]").count())
        self.page.locator("select[data-kind=prop][data-field=valueType]").select_option("ENUM")
        self.page.locator("input[data-kind=prop][data-field=enumValues]").fill("GOOD, HOLD, BAD")
        self.page.locator("input[data-kind=prop][data-field=enumValues]").press("Tab")
        self.act("save")
        self.act("validate")
        self.assertEqual("INVALID", self.page.evaluate("S.validation.status"))
        self.assertIn("主键必须是必填文本或整数", self.page.locator("#view").inner_text())
        self.assertIn("对象类型", self.page.locator("#view").inner_text())

    def test_ux06_delete_referenced_object_keeps_links_and_fix_entry(self):
        self.seed()
        self.page.locator("#tab-objects").click()
        self.page.locator("button[data-action=selectObject][data-index='1']").click()
        self.assertIn("引用影响提醒", self.page.locator("#view").inner_text())
        self.page.once("dialog", lambda dialog: dialog.accept())
        self.page.locator("button[data-action=delObject]").click()
        self.assertEqual(2, self.page.evaluate("S.local.linkTypes.length"))
        self.act("save")
        self.act("validate")
        self.assertEqual("INVALID", self.page.evaluate("S.validation.status"))
        self.assertIn("关系类型", self.page.locator("#view").inner_text())
        self.page.locator("button.issue").first.click()
        self.assertEqual("links", self.page.evaluate("S.tab"))

    def test_ux07_incomplete_relation_can_save_wip_but_cannot_freeze(self):
        self.page.locator("#tab-links").click()
        self.page.locator("button[data-action=addLink]").first.click()
        self.act("save")
        self.act("validate")
        self.assertEqual("INCOMPLETE", self.page.evaluate("S.validation.status"))
        self.act("freeze")
        self.assertEqual(0, self.page.evaluate("S.snapshots.length"))

    def test_ux08_conflict_retains_local_relation_changes(self):
        self.seed()
        self.page.locator("input[data-kind=link][data-field=label]").fill("本地关系草稿")
        self.page.locator("input[data-kind=link][data-field=label]").press("Tab")
        self.page.locator("#tab-check").click()
        self.page.locator("button[data-action=otherEditor]").click()
        self.act("save")
        self.assertTrue(self.page.evaluate("S.conflict && S.dirty"))
        self.assertEqual("本地关系草稿", self.page.evaluate("S.local.linkTypes[0].label"))
        self.assertIn("保存冲突", self.page.locator("#view").inner_text())

    def test_ux09_save_unknown_and_recover_by_server_comparison(self):
        self.seed()
        self.page.locator("#tab-check").click()
        self.page.locator("button[data-action=simulateSaveUnknown]").click()
        self.assertTrue(self.page.evaluate("S.pendingSave"))
        self.page.locator("button[data-action=reconcileSave]").click()
        self.assertFalse(self.page.evaluate("S.dirty"))
        self.assertEqual(self.page.evaluate("S.rev"), self.page.evaluate("S.baseRev"))

    def test_ux10_freeze_unknown_recovered_once(self):
        self.saved_valid()
        self.page.locator("#tab-check").click()
        self.page.locator("button[data-action=simulateFreezeUnknown]").click()
        self.assertEqual(1, self.page.evaluate("S.snapshots.length"))
        self.page.locator("button[data-action=reconcileFreeze]").click()
        self.assertEqual(1, self.page.evaluate("S.snapshots.length"))
        self.assertEqual("history", self.page.evaluate("S.tab"))

    def test_ux11_mobile_controls_no_horizon_scroll(self):
        self.page.set_viewport_size({"width": 390, "height": 844})
        self.seed()
        self.assertFalse(self.page.evaluate(
            "document.documentElement.scrollWidth > window.innerWidth + 1"))
        self.page.locator("#tab-objects").click()
        self.assertTrue(self.page.locator("button[data-action=addProp]").is_visible())
        self.page.locator("#tab-history").click()
        self.assertTrue(self.page.locator("#view").is_visible())

    def test_ux12_refresh_clears_mock_data_not_real_persistence(self):
        self.seed()
        self.act("save")
        self.page.reload()
        self.assertEqual(1, self.page.evaluate("S.local.objectTypes.length"))
        self.assertEqual(0, self.page.evaluate("S.local.linkTypes.length"))
        self.assertIn("内存", self.page.locator(".endnote").inner_text())


if __name__ == "__main__":
    unittest.main()
