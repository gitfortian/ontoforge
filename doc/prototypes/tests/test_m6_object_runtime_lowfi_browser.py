"""Chromium checks for the M6 Object Runtime UX-Low candidate.

These tests exercise only the static prototype and mocked states; they do not
call the runtime API or prove production Object Runtime behavior.
"""
from __future__ import annotations

import os
import unittest
from pathlib import Path

from playwright.sync_api import sync_playwright

PAGE = Path(__file__).resolve().parents[1] / "m6-object-runtime-lowfi.html"


class M6ObjectRuntimeLowFiBrowser(unittest.TestCase):
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
        self.page.get_by_role("heading", name="对象读取", exact=True).wait_for()

    def tearDown(self):
        if os.environ.get("UX_CAPTURE_SCREENSHOTS"):
            target = Path(os.environ.get("UX_SCREENSHOT_DIR", "/tmp/ontoforge-m6-lowfi-shots"))
            target.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(target / (self._testMethodName + ".png")), full_page=True)
        self.assertEqual(self.console_errors, [], "prototype should not emit uncaught browser errors")
        self.page.close()

    def test_success_distinguishes_present_null_missing_and_preserves_falsey_values(self):
        self.page.get_by_role("button", name="读取对象").click()
        result = self.page.locator("#object-result")
        result.wait_for(state="visible")
        text = result.inner_text()
        self.assertIn("请求范围完整", text)
        self.assertIn("DECIMAL", text)
        self.assertIn("BOOLEAN", text)
        self.assertIn("PRESENT", text)
        self.assertIn("NULL", text)
        self.assertIn("MISSING", text)
        self.assertIn("0", text)
        self.assertIn("false", text)
        self.assertIn("2 条一致物理行", text)
        evidence = self.page.locator("#evidence-result").inner_text()
        self.assertIn("freshness", evidence)
        self.assertIn("UNKNOWN", evidence)

    def test_property_subset_keeps_object_identity(self):
        self.page.locator("#property-choices input").nth(1).uncheck()
        self.page.locator("#property-choices input").nth(2).uncheck()
        self.page.locator("#property-choices input").nth(3).uncheck()
        self.page.get_by_role("button", name="读取对象").click()
        result = self.page.locator("#object-result").inner_text()
        self.assertIn("ObjectRef", result)
        self.assertIn("supplierName", result)
        self.assertNotIn("supplierTier", result)
        self.assertNotIn("creditLimit", result)

    def test_not_found_and_source_unavailable_are_distinct(self):
        self.page.get_by_label("演示场景").select_option("not-found")
        self.page.get_by_role("button", name="读取对象").click()
        self.assertIn("OBJECT_NOT_FOUND", self.page.locator("#error-result").inner_text())
        self.assertTrue(self.page.locator("#object-result").evaluate("(e) => e.classList.contains('hidden')"))
        self.page.get_by_label("演示场景").select_option("source-unavailable")
        self.page.get_by_role("button", name="读取对象").click()
        error = self.page.locator("#error-result").inner_text()
        self.assertIn("SOURCE_UNAVAILABLE", error)
        self.assertIn("503", error)
        self.assertNotIn("OBJECT_NOT_FOUND", error)

    def test_conflict_drift_budget_invalid_key_and_forbidden_never_return_payload(self):
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
                self.page.get_by_label("演示场景").select_option(scenario)
                self.page.get_by_role("button", name="读取对象").click()
                self.assertIn(code, self.page.locator("#error-result").inner_text())
                self.assertTrue(self.page.locator("#object-result").evaluate("(e) => e.classList.contains('hidden')"))
                self.assertIn(code, self.page.locator("#evidence-empty").inner_text())

    def test_binding_switch_changes_identity_domain_and_reset_clears_result(self):
        self.page.get_by_label("数据绑定").select_option("binding-20")
        self.assertEqual(self.page.locator("#identity-domain").inner_text(), "supplier-key-v1")
        self.page.get_by_role("button", name="读取对象").click()
        self.assertIn("supplier-key-v1", self.page.locator("#object-ref").inner_text())
        self.assertIn("binding-20 / revision 1", self.page.locator("#evidence-binding-version").inner_text())
        self.page.get_by_role("button", name="重置").click()
        self.assertTrue(self.page.locator("#result-empty").is_visible())
        self.assertEqual(self.page.locator("#object-key").input_value(), "SUP-001")


if __name__ == "__main__":
    unittest.main(verbosity=2)
