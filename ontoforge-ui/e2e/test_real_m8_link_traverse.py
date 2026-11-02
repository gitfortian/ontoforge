"""M8.1 real B0/JWT + PostgreSQL + React/Chromium entry and safe empty-state story.

The true positive FK/save/traverse/page/permission golden path runs in the
LinkBindingFullIntegrationTest with actual PG17 and Spring Boot HTTP.
This browser test exercises the *shipped* React workbench, without mocking APIs.
It does NOT represent signed-off M8.1 complete browser acceptance.
"""
import os
import unittest
from pathlib import Path

from playwright.sync_api import sync_playwright
from b0_fixture import assigned_package, headers

BASE = "http://127.0.0.1:5173"


class LinkTraverseRealBrowserTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.page = self.browser.new_page(
            viewport={"width": 1380, "height": 920},
            locale="zh-CN",
            extra_http_headers=headers(),
        )
        self.errors = []
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))
        self.package = assigned_package()
        self.page.goto(BASE + "/#/mvp/object-sets")
        self.page.get_by_role("heading", name="对象集合查询", exact=True).wait_for(timeout=18000)

    def tearDown(self):
        if os.getenv("E2E_SHOTS"):
            folder = Path(os.environ["E2E_SHOTS"])
            folder.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(folder / ("m8_" + self._testMethodName + ".png")), full_page=True)
        self.assertEqual(self.errors, [])
        self.page.close()

    def test_m8_is_integrated_not_a_separate_menu_and_empty_state_is_safe(self):
        page = self.page
        self.assertEqual(page.locator(".sidebar .nav-link").filter(has_text="M8").count(), 0)
        panel = page.get_by_role("region", name="关系与一跳遍历 M8")
        self.assertEqual(panel.count(), 1)
        panel.get_by_role("button", name="展开关系工作台").click()
        panel.get_by_text("1 选择关系与端点").wait_for()
        self.assertTrue(panel.get_by_role("button", name="读取当前配置").is_disabled())
        page.locator("#object-set-package").fill(self.package)
        panel.get_by_role("button", name="读取当前配置").click()
        panel.get_by_text("尚无已保存且通过 FULL 校验的 Object Binding").wait_for(timeout=15000)
        self.assertTrue(panel.get_by_role("button", name="执行 FULL 验证").is_disabled())
        self.assertTrue(panel.get_by_role("button", name="执行一跳查询").is_disabled())
        # Product UI exposes the new bounded selector without pretending that a
        # missing M5 endpoint permits a real SQL filter or query.
        panel.get_by_label("M8 Root Selector").select_option("BOUNDED_ROOT_FILTER")
        self.assertTrue(panel.get_by_label("M8 Root Filter Field").is_visible())
        self.assertTrue(panel.get_by_label("M8 Root Filter Value").is_visible())
        self.assertTrue(panel.get_by_label("M8 Target Filter Field").is_visible())
        self.assertTrue(panel.get_by_role("button", name="执行一跳查询").is_disabled())
        panel.get_by_label("M8 Root Selector").select_option("OBJECT_REF")
        # The real React page must separate object pages from scalar aggregates:
        # unsupported/unbound numeric source must not enable SUM execution.
        panel.get_by_label("M8 Result Mode").select_option("COUNT_DISTINCT")
        self.assertTrue(panel.get_by_role("button", name="执行一跳查询").is_disabled())
        panel.get_by_label("M8 Result Mode").select_option("SUM")
        self.assertTrue(panel.get_by_label("M8 Sum Field").is_visible())
        self.assertTrue(panel.get_by_role("button", name="执行一跳查询").is_disabled())
        panel.get_by_label("M8 Result Mode").select_option("PAGE")
        self.assertTrue(panel.get_by_label("M8 Sort Field").is_visible())
        self.assertTrue(panel.get_by_label("M8 Project Field").is_visible())

        self.assertEqual(panel.locator(".m8-links tr").count(), 0)
        self.assertNotIn("SUCCESS", panel.locator(".m8-result").all_text_contents())

        # Narrow-screen usability is checked against the actual React markup.
        page.set_viewport_size({"width": 375, "height": 812})
        self.assertTrue(panel.get_by_role("button", name="收起工作台").is_visible())
        panel.get_by_role("button", name="收起工作台").click()
        self.assertEqual(panel.locator(".m8-steps").count(), 0)


if __name__ == "__main__":
    unittest.main()
