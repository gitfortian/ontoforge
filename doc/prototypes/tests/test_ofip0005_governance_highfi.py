import os
import unittest
from pathlib import Path

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[3]
HTML = ROOT / "doc" / "prototypes" / "ofip-0005-governance-highfi.html"


class OFIP0005GovernanceHighFiBrowserTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pw = sync_playwright().start()
        cls.browser = cls.pw.chromium.launch(headless=True)

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.pw.stop()

    def setUp(self):
        self.page = self.browser.new_page(viewport={"width": 1440, "height": 1000})
        self.page.goto(HTML.as_uri())
        self.page.wait_for_load_state("domcontentloaded")

    def tearDown(self):
        if os.environ.get("UX_CAPTURE_SCREENSHOTS") == "1":
            out = Path(os.environ.get("UX_SCREENSHOT_DIR", "/tmp/ofip-0005-ux-highfi-shots"))
            out.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(out / (self.id().split(".")[-1] + ".png")), full_page=True)
        self.page.close()

    def nav(self, name):
        self.page.locator(".sidebar").get_by_role("button", name=name, exact=True).click()

    def test_h01_overview_uses_product_language_and_current_state(self):
        body = self.page.locator("body").inner_text()
        self.assertIn("模型包概览", body)
        self.assertIn("当前生效版本", body)
        self.assertIn("需要我处理", body)
        self.assertNotIn("ChangeSet", body)
        self.assertNotIn("SchemaRelease", body)
        self.assertNotIn("ActivationEvent", body)

    def test_h02_navigation_is_one_model_governance_workspace(self):
        buttons = self.page.locator(".sidebar .nav button")
        self.assertEqual(buttons.count(), 7)
        self.assertEqual(
            [x.strip() for x in buttons.all_text_contents()],
            ["▣ 模型治理", "概览", "我的变更 2", "待我审核 1", "正式版本", "生效范围", "治理记录"],
        )

    def test_h03_prepare_change_requires_business_reason(self):
        self.nav("我的变更 2")
        self.page.get_by_role("button", name="＋ 准备模型变更").click()
        self.page.get_by_role("button", name="提交审核").click()
        self.assertTrue(self.page.locator("#drawer.open").count() == 1)
        self.assertEqual(self.page.locator("#changeReason").input_value(), "")
        self.page.locator("#changeReason").fill("生产现场确认一个工单可拆分多个批次")
        self.page.get_by_role("button", name="提交审核").click()
        self.assertIn("已创建模型变更申请", self.page.locator("body").inner_text())

    def test_h04_submit_copy_explains_content_is_fixed(self):
        self.nav("我的变更 2")
        self.page.get_by_role("button", name="＋ 准备模型变更").click()
        self.assertIn("提交以后，这份变更内容会固定", self.page.locator("#drawer").inner_text())

    def test_h05_review_list_explains_authorization_boundary(self):
        self.nav("待我审核 1")
        body = self.page.locator("body").inner_text()
        self.assertIn("你有权限审核、且不是你本人提交", body)
        self.assertIn("领域审核者", body)
        self.assertIn("消费者影响未知", body)

    def test_h06_review_detail_orders_diff_risk_decision(self):
        self.nav("待我审核 1")
        self.page.get_by_role("button", name="开始审核 →").click()
        self.assertIn("查看变化", self.page.locator("body").inner_text())
        self.assertIn("版本变化", self.page.locator("body").inner_text())
        self.assertIn("风险与检查", self.page.locator("body").inner_text())
        self.assertIn("审核决定", self.page.locator("body").inner_text())
        self.assertIn("MANY_TO_ONE", self.page.locator("body").inner_text())

    def test_h07_technical_details_are_secondary(self):
        self.nav("待我审核 1")
        self.page.get_by_role("button", name="开始审核 →").click()
        self.page.get_by_text("查看技术定位信息", exact=True).click()
        self.assertIn("模型路径", self.page.locator("body").inner_text())

    def test_h08_review_approve_has_consequence(self):
        self.nav("待我审核 1")
        self.page.get_by_role("button", name="开始审核 →").click()
        self.page.get_by_role("button", name="批准").click()
        self.assertIn("可以进入正式版本创建阶段", self.page.locator("#decisionModal").inner_text())

    def test_h09_review_return_requires_explanation_in_product_contract(self):
        self.nav("待我审核 1")
        self.page.get_by_role("button", name="开始审核 →").click()
        self.page.get_by_role("button", name="退回修改").click()
        self.assertIn("退回后", self.page.locator("#decisionModal").inner_text())
        self.assertIn("重新提交", self.page.locator("#decisionModal").inner_text())

    def test_h10_release_separates_release_from_activation(self):
        self.nav("正式版本")
        body = self.page.locator("body").inner_text()
        self.assertIn("0.5.0", body)
        self.assertIn("待创建", body)
        self.assertIn("尚未形成正式版本", body)
        self.assertIn("不会自动让任何业务范围切换", body)

    def test_h11_release_confirmation_repeats_current_and_target_state(self):
        self.nav("正式版本")
        self.page.get_by_role("button", name="创建正式版本").click()
        self.assertIn("0.5.0", self.page.locator("#releaseModal").inner_text())
        self.assertIn("0.4.0 · production-core", self.page.locator("#releaseModal").inner_text())
        self.assertIn("不会自动让任何业务范围使用它", self.page.locator("#releaseModal").inner_text())
        self.page.get_by_role("button", name="确认创建正式版本").click()
        self.assertIn("正式版本 0.5.0 已创建", self.page.locator("body").inner_text())

    def test_h12_activation_requires_reason(self):
        self.nav("生效范围")
        self.page.get_by_role("button", name="让 0.5.0 生效").click()
        self.page.get_by_role("button", name="确认生效").click()
        self.assertIn("请填写生效原因后再继续", self.page.locator("#activationModal").inner_text())

    def test_h13_activation_unknown_result_has_recovery_path(self):
        self.nav("生效范围")
        self.page.get_by_role("button", name="让 0.5.0 生效").click()
        self.page.locator("#activationReason").fill("生产业务口径已完成领域确认")
        self.page.get_by_role("button", name="确认生效").click()
        self.assertIn("操作结果暂时无法确认", self.page.locator("#recoveryModal").inner_text())
        self.assertIn("不要重复点击", self.page.locator("#recoveryModal").inner_text())
        self.assertIn("核对当前生效版本", self.page.locator("#recoveryModal").inner_text())

    def test_h14_recovery_does_not_claim_activation_success(self):
        self.nav("生效范围")
        self.page.get_by_role("button", name="让 0.5.0 生效").click()
        self.page.locator("#activationReason").fill("测试恢复")
        self.page.get_by_role("button", name="确认生效").click()
        self.page.get_by_role("button", name="核对当前生效版本").click()
        self.assertIn("仍为 0.4.0", self.page.locator("body").inner_text())
        self.assertIn("未重复创建操作", self.page.locator("body").inner_text())

    def test_h15_release_list_can_enter_activation_without_merging_the_two_actions(self):
        self.nav("正式版本")
        self.page.get_by_role("button", name="选择生效范围").click()
        self.assertIn("生效范围", self.page.locator("body").inner_text())
        self.assertIn("让 0.5.0 生效", self.page.locator("body").inner_text())

    def test_h16_rollback_is_a_version_switch_not_deletion(self):
        self.nav("正式版本")
        self.page.get_by_role("button", name="切回此版本").click()
        self.assertIn("切回历史版本 0.3.0", self.page.locator("#activationModal").inner_text())
        self.assertIn("目标版本", self.page.locator("#activationModal").inner_text())
        self.assertIn("切回历史版本不会删除原正式版本", self.page.locator("body").inner_text())

    def test_h17_history_has_full_governance_chain(self):
        self.nav("治理记录")
        body = self.page.locator("body").inner_text()
        for text in ["已提交", "审核通过", "正式发行", "生效", "历史版本"]:
            self.assertIn(text, body)
        self.assertIn("不会删除被切回的版本", body)
        self.assertIn("不会撤销已经发生的 MES / ERP 业务操作", body)

    def test_h18_narrow_desktop_has_no_horizontal_overflow(self):
        self.page.set_viewport_size({"width": 390, "height": 844})
        self.page.reload()
        self.page.wait_for_load_state("domcontentloaded")
        overflow = self.page.evaluate("document.documentElement.scrollWidth > document.documentElement.clientWidth")
        self.assertFalse(overflow)


if __name__ == "__main__":
    unittest.main()
