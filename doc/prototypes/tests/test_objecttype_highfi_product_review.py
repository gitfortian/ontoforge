"""Extra UX-Gate-High reviewer regressions: semantic/editor correctness.

Design-only in-memory HTML mock; does not test PostgreSQL, server APIs or permissions.
"""
from __future__ import annotations

import unittest
import os
from pathlib import Path
from playwright.sync_api import sync_playwright

HIGH = (Path(__file__).resolve().parents[1] / "ofip-0002-objecttype-highfi.html").as_uri()


class HighFiProductReview(unittest.TestCase):
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
        self.errors = []
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))
        self.page.goto(HIGH)
        self.page.get_by_role("heading", name="模型包", exact=True).wait_for()
        self.page.get_by_role("button", name="新建第一个模型包").click()
        self.page.locator("#package-id").fill("manufacturing.production")
        self.page.get_by_role("button", name="创建空草稿并进入").click()
        self.page.get_by_role("button", name="新建首个对象类型").first.click()
        self.page.locator("#obj-id").fill("production-batch")
        self.page.locator("#obj-api").fill("ProductionBatch")

    def tearDown(self):
        if os.environ.get("UX_CAPTURE_SCREENSHOTS"):
            root = Path(os.environ.get("UX_SCREENSHOT_DIR", "/tmp/ontoforge-ux-shots"))
            root.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(
                path=str(root / ("HighFiProductReview__" + self._testMethodName + ".png")),
                full_page=True,
            )
        self.assertEqual(self.errors, [], "Uncaught browser exception")
        self.page.close()

    def add_field(self):
        buttons = self.page.get_by_role("button", name="＋ 添加第一个属性")
        if buttons.count():
            buttons.click()
        else:
            self.page.get_by_role("button", name="＋ 添加属性").click()
        self.page.locator("#p0-id").fill("batch-id")
        self.page.locator("#p0-api").fill("batchId")

    def save(self):
        self.page.get_by_role("button", name="保存编辑进度", exact=True).click()

    def test_dirty_edit_never_presents_old_validation_as_current(self):
        self.add_field()
        self.page.locator("#p0-required").check()
        self.page.locator("#primary-ref").select_option("batch-id")
        self.save()
        self.page.get_by_role("button", name="检查模型完整性").click()
        self.assertEqual(self.page.evaluate("state.records[0].validation.status"), "VALID")
        self.assertIn("校验通过", self.page.locator("#validation-current-state").inner_text())

        self.page.locator("#obj-desc").fill("新的本地定义")
        self.assertIn("待保存", self.page.locator("#validation-current-state").inner_text())
        self.assertTrue(self.page.locator("#validate-current").is_disabled())
        self.assertIn("旧版本", self.page.locator("#validation-issues-wrap").inner_text())
        self.save()
        self.assertIn("未校验", self.page.locator("#validation-current-state").inner_text())
        self.assertIsNone(self.page.evaluate("state.records[0].validation"))

    def test_old_invalid_errors_clear_on_edit(self):
        self.save()
        self.page.get_by_role("button", name="检查模型完整性").click()
        self.assertEqual(self.page.locator("#obj-id").get_attribute("aria-invalid"), None)
        self.assertTrue(self.page.locator(".issue-link").count())
        self.page.locator("#obj-id").fill("production-lot")
        self.assertEqual(self.page.locator(".issue-link").count(), 0)
        self.assertIn("待保存", self.page.locator("#validation-current-state").inner_text())

    def test_primary_key_options_only_include_required_string_or_integer(self):
        self.add_field()
        self.assertNotIn("batch-id", self.page.locator("#primary-ref").locator("option").all_text_contents())
        self.page.locator("#p0-required").check()
        self.assertIn("batch-id", self.page.locator("#primary-ref").locator("option").all_text_contents()[1])
        self.page.locator("#primary-ref").select_option("batch-id")
        self.page.locator("#p0-type").select_option("DECIMAL")
        self.assertEqual(self.page.locator("#primary-ref").input_value(), "")
        self.page.locator("#p0-type").select_option("INTEGER")
        self.page.locator("#primary-ref").select_option("batch-id")
        self.page.locator("#p0-required").uncheck()
        self.assertEqual(self.page.locator("#primary-ref").input_value(), "")

    def test_enum_only_can_be_entered_for_enum_and_not_leaked_into_string(self):
        self.add_field()
        enum = self.page.locator("#p0-enum")
        self.assertTrue(enum.is_disabled())
        self.page.locator("#p0-type").select_option("ENUM")
        self.assertTrue(enum.is_enabled())
        enum.fill("PLANNED, RUNNING")
        self.save()
        self.assertEqual(self.page.evaluate("state.records[0].objectTypes[0].properties[0].enumValues"),
                         ["PLANNED", "RUNNING"])
        self.page.locator("#p0-type").select_option("STRING")
        self.assertTrue(enum.is_disabled())
        self.assertEqual(enum.input_value(), "")
        self.save()
        self.assertEqual(self.page.evaluate("state.records[0].objectTypes[0].properties[0].enumValues"), [])

    def test_dynamic_property_label_is_clickable_and_follows_its_input(self):
        self.add_field()
        container = self.page.locator('.prop[data-prop="0"]')
        container.locator('label[for="p0-api"]').click()
        self.assertEqual(self.page.evaluate("document.activeElement.id"), "p0-api")
        container.locator('label[for="p0-type"]').click()
        self.assertEqual(self.page.evaluate("document.activeElement.id"), "p0-type")

    def test_small_information_text_contrast_at_least_wcag_aa(self):
        """Small readable notes on white and light-blue surfaces need >=4.5:1 contrast."""
        def ratio(css_color, against):
            def linear(rgb):
                r, g, b = rgb
                vals = [(v / 255.0) for v in (r, g, b)]
                vals = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in vals]
                return .2126 * vals[0] + .7152 * vals[1] + .0722 * vals[2]

            def extract(value):
                if value.startswith("#"):
                    return tuple(int(value[i:i+2], 16) for i in (1, 3, 5))
                return tuple(int(v.strip()) for v in value[value.find("(")+1:value.find(")")].split(",")[:3])

            a, b = linear(extract(css_color)), linear(extract(against))
            return (max(a, b) + .05) / (min(a, b) + .05)

        self.page.get_by_role("heading", name="编辑对象类型").wait_for()
        for selector, background in [
            (".head p", "#f6f8fb"),
            (".panelhead p", "#ffffff"),
            (".caption", "#ffffff"),
            (".topbar", "#f6f8fb"),
        ]:
            color = self.page.locator(selector).first.evaluate("el=>getComputedStyle(el).color")
            self.assertGreaterEqual(ratio(color, background), 4.5,
                                    f"Insufficient text contrast: {selector} {color} against {background}")

    def test_mobile_editor_has_no_horizontal_viewport_overflow(self):
        self.page.set_viewport_size({"width": 390, "height": 844})
        self.add_field()
        self.assertLessEqual(self.page.evaluate("document.documentElement.scrollWidth"),
                             self.page.evaluate("window.innerWidth") + 2)
        self.assertTrue(self.page.locator("#primary-ref").is_visible())


if __name__ == "__main__":
    unittest.main(verbosity=2)
