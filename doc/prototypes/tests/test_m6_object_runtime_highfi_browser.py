"""Real Chromium regression for the M6 Object Runtime UX-High prototype.

These tests validate only the static prototype's mocked interaction states. They
do not exercise production Runtime APIs, authorization, or PostgreSQL.
"""
from __future__ import annotations

import os
import unittest
from pathlib import Path

from playwright.sync_api import sync_playwright

PAGE = Path(__file__).resolve().parents[1] / "m6-object-runtime-highfi.html"


class M6ObjectRuntimeHighFiBrowser(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.page = self.browser.new_page(viewport={"width": 1440, "height": 1000}, locale="zh-CN")
        self.console_errors: list[str] = []
        self.page.on("pageerror", lambda error: self.console_errors.append(str(error)))
        self.page.goto(PAGE.as_uri())
        self.page.get_by_role("heading", name="对象读取", exact=True).wait_for()

    def tearDown(self):
        if os.environ.get("UX_CAPTURE_SCREENSHOTS"):
            target = Path(os.environ.get("UX_SCREENSHOT_DIR", "/tmp/ontoforge-m6-prototype-shots"))
            target.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(target / (self._testMethodName + ".png")), full_page=True)
        self.assertEqual(self.console_errors, [], "prototype should not emit uncaught browser errors")
        self.page.close()

    def test_success_shows_identity_typed_values_and_source_evidence(self):
        self.page.get_by_role("button", name="读取对象").click()
        result = self.page.locator("#object-result")
        self.assertTrue(result.is_visible())
        text = result.inner_text()
        self.assertIn("ObjectRef", text)
        self.assertIn("PRESENT", text)
        self.assertIn("NULL", text)
        self.assertIn("MISSING", text)
        self.assertIn("DECIMAL", text)
        self.assertIn("BOOLEAN", text)
        self.assertIn("0", text)
        self.assertIn("false", text)
        self.assertIn("2 条一致物理行", text)
        evidence = self.page.locator("#evidence-result").inner_text()
        self.assertIn("binding-21", evidence)
        self.assertIn("sourceReadRef", evidence)
        self.assertIn("UNKNOWN", evidence)

    def test_property_subset_keeps_full_identity_and_updates_count(self):
        self.page.locator("#property-choices input").nth(1).uncheck()
        self.page.locator("#property-choices input").nth(2).uncheck()
        self.page.locator("#property-choices input").nth(3).uncheck()
        self.assertIn("3 项已选", self.page.locator("#selected-count").inner_text())
        self.page.get_by_role("button", name="读取对象").click()
        result = self.page.locator("#object-result").inner_text()
        self.assertIn("ObjectRef", result)
        self.assertIn("supplierName", result)
        self.assertNotIn("supplierTier", result)
        self.assertNotIn("creditLimit", result)

    def test_not_found_and_source_unavailable_have_different_recovery(self):
        self.page.get_by_label("预览状态").select_option("not-found")
        self.page.get_by_role("button", name="读取对象").click()
        self.assertIn("OBJECT_NOT_FOUND", self.page.locator("#error-result").inner_text())
        self.assertTrue(self.page.locator("#object-result").evaluate("(e) => e.hidden"))
        self.assertIn("修改业务键", self.page.locator("#recovery-primary").inner_text())
        self.page.get_by_label("预览状态").select_option("source-unavailable")
        self.page.get_by_role("button", name="读取对象").click()
        error = self.page.locator("#error-result").inner_text()
        self.assertIn("SOURCE_UNAVAILABLE", error)
        self.assertIn("503", error)
        self.assertNotIn("OBJECT_NOT_FOUND", error)
        self.assertIn("重新读取", self.page.locator("#recovery-primary").inner_text())

    def test_conflicts_drift_budget_invalid_key_and_forbidden_never_return_payload(self):
        scenarios = [
            ("identity-conflict", "OBJECT_IDENTITY_CONFLICT"),
            ("property-conflict", "PROPERTY_VALUE_CONFLICT"),
            ("schema-drift", "BINDING_SCHEMA_DRIFT"),
            ("budget", "QUERY_BUDGET_EXCEEDED"),
            ("invalid-key", "INVALID_OBJECT_KEY"),
            ("forbidden", "FORBIDDEN"),
        ]
        for scenario, code in scenarios:
            with self.subTest(scenario=scenario):
                self.page.get_by_label("预览状态").select_option(scenario)
                self.page.get_by_role("button", name="读取对象").click()
                self.assertIn(code, self.page.locator("#error-result").inner_text())
                self.assertTrue(self.page.locator("#object-result").evaluate("(e) => e.hidden"))
                self.assertIn(code, self.page.locator("#evidence-empty").inner_text())

    def test_binding_revision_and_identity_domain_stay_consistent(self):
        self.page.get_by_label("数据绑定版本").select_option("binding-20")
        self.assertIn("supplier-key-v1", self.page.locator("#context-domain").inner_text())
        self.page.get_by_role("button", name="读取对象").click()
        self.assertIn("identityDomain=supplier-key-v1", self.page.locator("#object-ref").inner_text())
        evidence = self.page.locator("#evidence-result").inner_text()
        self.assertIn("binding-20", evidence)
        self.assertIn("revision 1", evidence)
        self.assertIn("supplier-key-v1", evidence)

    def test_reset_clears_payload_and_restores_default_context(self):
        self.page.get_by_role("button", name="读取对象").click()
        self.page.get_by_role("button", name="重置请求").click()
        self.assertTrue(self.page.locator("#result-empty").is_visible())
        self.assertTrue(self.page.locator("#evidence-empty").is_visible())
        self.assertEqual(self.page.locator("#object-key").input_value(), "SUP-001")
        self.assertIn("supplier-key-v2", self.page.locator("#context-domain").inner_text())

    def test_mobile_layout_has_no_horizontal_page_overflow(self):
        self.page.set_viewport_size({"width": 390, "height": 844})
        self.page.wait_for_timeout(50)
        overflow = self.page.evaluate("document.documentElement.scrollWidth > window.innerWidth + 1")
        self.assertFalse(overflow, "mobile viewport should not overflow horizontally")


if __name__ == "__main__":
    unittest.main(verbosity=2)
