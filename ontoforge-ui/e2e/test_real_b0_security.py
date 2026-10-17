"""Real production JWT decoder/JWKS through Vite, plus the unchanged product stories."""
import json
import unittest
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from b0_fixture import CONFIG, headers, assigned_package

BASE = "http://127.0.0.1:5173/api"


def request(path, auth=None, method="GET", payload=None):
    req = Request(BASE + path, method=method,
                  headers={"Content-Type": "application/json", **(auth or {})},
                  data=json.dumps(payload).encode() if payload is not None else None)
    try:
        with urlopen(req, timeout=12) as reply:
            return reply.status, json.load(reply)
    except HTTPError as reply:
        return reply.code, json.load(reply)


class B0ProductionDecoderE2ETest(unittest.TestCase):
    def test_unauthenticated_browser_cannot_enter_model_workspace(self):
        from playwright.sync_api import sync_playwright
        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True, args=['--no-sandbox'])
            try:
                page = browser.new_page(viewport={'width': 1280, 'height': 900})
                page.goto('http://127.0.0.1:5173/#/model/packages')
                page.get_by_role('heading', name='可信身份验证').wait_for()
                self.assertEqual(page.get_by_role('button', name='创建模型包').count(), 0)
                self.assertEqual(page.locator('#obj-id').count(), 0)
                response = page.request.get(BASE + '/model/drafts')
                self.assertEqual(response.status, 401)
            finally:
                browser.close()

    def test_actual_oidc_authorization_code_pkce_browser_login(self):
        from playwright.sync_api import sync_playwright
        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True, args=["--no-sandbox"])
            try:
                page = browser.new_page(viewport={"width": 1370, "height": 900})
                page.goto("http://127.0.0.1:5173/#/model/packages")
                page.get_by_role("heading", name="可信身份验证").wait_for()
                page.get_by_role("button", name="使用组织账号登录").click()
                try:
                    page.get_by_role("heading", name="模型包", exact=True).wait_for(timeout=20000)
                except Exception:
                    # No access tokens, authorization codes, or secrets are logged.
                    print("OIDC_FAIL_PATH:", page.url.split("?")[0])
                    print("OIDC_FAIL_SCREEN:", page.locator("body").inner_text()[:650])
                    raise
                self.assertEqual(page.get_by_role("button", name="创建模型包").count(), 1)
                self.assertNotIn("code=", page.url, "OAuth code must be scrubbed from URL")
                self.assertIn("#/model/packages", page.url)
                # Authenticated UI now uses actual Bearer fetched via browser OIDC, not test headers.
                page.get_by_role("button", name="创建模型包").click()
                page.locator("#create-package-id").fill(assigned_package())
                page.get_by_role("button", name="创建并继续").click()
                page.locator("#obj-id").wait_for(timeout=20000)
            finally:
                browser.close()

    def test_viewer_ui_has_no_create_and_displays_permission_guidance(self):
        from playwright.sync_api import sync_playwright
        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True, args=["--no-sandbox"])
            try:
                page = browser.new_page(viewport={"width": 1280, "height": 900},
                                        extra_http_headers=headers("viewer"))
                page.goto("http://127.0.0.1:5173/#/model/packages")
                page.get_by_role("heading", name="模型包", exact=True).wait_for()
                self.assertEqual(page.get_by_role("button", name="创建模型包").count(), 0)
                self.assertEqual(page.get_by_role("button", name="新建第一个模型包").count(), 0)
                page.get_by_text("当前身份没有可编辑的模型包", exact=False).wait_for()
            finally:
                browser.close()

    def test_editor_ui_chooses_only_explicitly_assigned_package(self):
        from playwright.sync_api import sync_playwright
        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True, args=["--no-sandbox"])
            try:
                page = browser.new_page(viewport={"width": 1280, "height": 900},
                                        extra_http_headers=headers())
                page.goto("http://127.0.0.1:5173/#/model/packages")
                page.get_by_role("heading", name="模型包", exact=True).wait_for()
                page.get_by_role("button", name="创建模型包").click()
                selector = page.locator("#authorized-package-choice")
                self.assertGreater(selector.locator("option").count(), 1)
                assigned = assigned_package()
                selector.select_option(assigned)
                self.assertEqual(page.locator("#create-package-id").input_value(), assigned)
                page.locator("#create-package-id").fill("manufacturing.notassigned")
                self.assertTrue(page.get_by_role("button", name="创建并继续").is_disabled())
                selector.select_option(assigned)
                self.assertTrue(page.get_by_role("button", name="创建并继续").is_enabled())
            finally:
                browser.close()

    def test_unsigned_and_invalid_signature_are_401_safe_json(self):
        self.assertEqual(request("/model/drafts"),
                         (401, {"code": "AUTHENTICATION_REQUIRED", "message": "需要登录后才能访问该资源"}))
        parts = CONFIG["editorToken"].split(".")
        parts[2] = ("A" if parts[2][0] != "A" else "B") + parts[2][1:]
        status, body = request("/model/drafts", {"Authorization": "Bearer " + ".".join(parts)})
        self.assertEqual((status, body["code"]), (401, "AUTHENTICATION_REQUIRED"))

    def test_real_signed_editor_identity_and_package_grants(self):
        status, body = request("/me", headers())
        self.assertEqual(status, 200)
        self.assertEqual(body["subject"], CONFIG["editorSubject"])
        self.assertEqual(body["issuer"], CONFIG["issuer"])
        status, body = request("/model/drafts", headers(), "POST",
                               {"packageId": "acceptance.unassigned" + uuid.uuid4().hex})
        self.assertEqual((status, body["code"]), (403, "ACCESS_DENIED"))

    def test_viewer_outsider_and_spoofed_headers_cannot_read_editor_draft(self):
        status, body = request("/model/drafts", headers(), "POST", {"packageId": assigned_package()})
        self.assertEqual(status, 201)
        path = "/model/drafts/" + body["draftId"]
        for role in ("viewer", "outsider"):
            status, body = request(path, {**headers(role), "X-Role": "OWNER",
                                         "X-User-Id": CONFIG["editorSubject"]})
            self.assertEqual((status, body["code"]), (403, "ACCESS_DENIED"))
            self.assertNotIn("editingContent", body)


if __name__ == "__main__":
    unittest.main(verbosity=2)
