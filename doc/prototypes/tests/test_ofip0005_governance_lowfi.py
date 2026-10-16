import os
import unittest
from pathlib import Path

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[3]
HTML = ROOT / "doc" / "prototypes" / "ofip-0005-governance-lowfi.html"


class OFIP0005GovernanceLowFiBrowserTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pw = sync_playwright().start()
        cls.browser = cls.pw.chromium.launch(headless=True)
        cls.page = cls.browser.new_page(viewport={"width": 1440, "height": 1000})
        cls.page.goto(HTML.as_uri())
        cls.page.wait_for_load_state("domcontentloaded")

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.pw.stop()

    def setUp(self):
        self.page.goto(HTML.as_uri())
        self.page.wait_for_load_state("domcontentloaded")

    def tearDown(self):
        if os.environ.get("UX_CAPTURE_SCREENSHOTS") == "1":
            out = Path(os.environ.get("UX_SCREENSHOT_DIR", "/tmp/ofip-0005-ux-shots"))
            out.mkdir(parents=True, exist_ok=True)
            name = self.id().split(".")[-1] + ".png"
            self.page.screenshot(path=str(out / name), full_page=True)

    def test_01_product_navigation_has_six_user_facing_entries(self):
        nav = self.page.locator(".nav button")
        self.assertEqual(nav.count(), 6)
        self.assertEqual(
            nav.all_text_contents(),
            ["模型包概览", "我的变更", "待我审核", "正式版本", "生效范围", "治理记录"],
        )

    def test_02_overview_uses_product_terms_not_resource_ids(self):
        body = self.page.locator("body").inner_text()
        self.assertIn("当前生效版本", body)
        self.assertIn("最近一次模型变更", body)
        self.assertNotIn("SchemaRelease", body)
        self.assertNotIn("ActivationEvent", body)

    def test_03_submit_change_requires_explicit_content_lock_confirmation(self):
        self.page.get_by_role("button", name="我的变更", exact=True).click()
        self.page.get_by_role("button", name="准备提交变更").click()
        self.page.get_by_role("button", name="提交审核").click()
        self.page.on("dialog", lambda dialog: dialog.dismiss())
        # A missing confirmation must not leave the drawer closed.
        self.assertTrue(self.page.locator("#drawer.open").count() == 1)
        self.page.locator("#lock").check()
        self.page.get_by_role("button", name="提交审核").click()
        self.assertEqual(self.page.locator("#drawer.open").count(), 0)
        self.assertIn("我的变更", self.page.locator("body").inner_text())

    def test_04_review_page_excludes_self_and_explains_role(self):
        self.page.get_by_role("button", name="待我审核", exact=True).click()
        body = self.page.locator("body").inner_text()
        self.assertIn("只显示你有权限审核、且不属于你本人提交", body)
        self.assertIn("领域审核者", body)
        self.assertIn("CS-102", body)

    def test_05_review_detail_shows_business_diff_before_decision(self):
        self.page.get_by_role("button", name="待我审核", exact=True).click()
        self.page.get_by_role("button", name="开始审核").click()
        body = self.page.locator("body").inner_text()
        self.assertIn("原版本 0.4.0", body)
        self.assertIn("候选 0.5.0", body)
        self.assertIn("潜在破坏性变化", body)
        self.assertIn("消费者影响", body)
        self.assertIn("批准", body)
        self.assertIn("退回修改", body)
        self.assertIn("拒绝", body)

    def test_06_each_review_decision_explains_consequence(self):
        self.page.get_by_role("button", name="待我审核", exact=True).click()
        self.page.get_by_role("button", name="开始审核").click()
        for action, expected in [
            ("批准", "实际服务端还必须校验可信身份"),
            ("退回修改", "原审核证据保留"),
            ("拒绝", "该审核轮次终止"),
        ]:
            self.page.get_by_role("button", name=action).click()
            self.assertIn(expected, self.page.locator("#decisionHint").inner_text())

    def test_07_release_page_separates_published_from_active(self):
        self.page.get_by_role("button", name="正式版本", exact=True).click()
        body = self.page.locator("body").inner_text()
        self.assertIn("正式版本", body)
        self.assertIn("已发行", body)
        self.assertIn("尚未生效", body)
        self.assertIn("当前生效", body)

    def test_08_release_confirmation_warns_that_release_is_not_activation(self):
        self.page.get_by_role("button", name="正式版本", exact=True).click()
        self.page.get_by_role("button", name="创建正式版本 0.5.0").click()
        body = self.page.locator("body").inner_text()
        self.assertIn("创建正式版本", body)
        self.assertIn("正式发行与“让业务范围使用它”是两个不同动作", body)

    def test_09_activation_requires_reason_and_unknown_result_has_recovery_path(self):
        self.page.get_by_role("button", name="生效范围", exact=True).click()
        self.page.get_by_role("button", name="将 0.5.0 生效").click()
        self.page.get_by_role("button", name="确认生效").click()
        self.assertIn("请填写生效原因后再继续", self.page.locator("body").inner_text())
        self.page.locator("#activationReason").fill("生产业务口径已完成领域确认")
        self.page.get_by_role("button", name="确认生效").click()
        body = self.page.locator("body").inner_text()
        self.assertIn("操作结果暂时无法确认", body)
        self.assertIn("不要重复点击", body)
        self.assertIn("核对当前生效版本", body)

    def test_10_history_distinguishes_rollback_from_external_business_undo(self):
        self.page.get_by_role("button", name="治理记录", exact=True).click()
        body = self.page.locator("body").inner_text()
        self.assertIn("提出变更", body)
        self.assertIn("审核通过", body)
        self.assertIn("正式发行", body)
        self.assertIn("生效", body)
        self.assertIn("回退不是删除 0.5.0", body)
        self.assertIn("切回模型版本不会撤销已经发生的业务操作", body)

    def test_11_mobile_width_has_no_horizontal_overflow(self):
        self.page.set_viewport_size({"width": 390, "height": 844})
        self.page.reload()
        overflow = self.page.evaluate("document.documentElement.scrollWidth > document.documentElement.clientWidth")
        self.assertFalse(overflow)

    def test_12_recovery_returns_to_real_current_state_screen(self):
        self.page.goto(HTML.as_uri())
        self.page.get_by_role("button", name="生效范围", exact=True).click()
        self.page.get_by_role("button", name="将 0.5.0 生效").click()
        self.page.locator("#activationReason").fill("测试恢复")
        self.page.get_by_role("button", name="确认生效").click()
        self.page.get_by_role("button", name="核对当前生效版本").click()
        self.assertIn("当前范围", self.page.locator("body").inner_text())


if __name__ == "__main__":
    unittest.main()
