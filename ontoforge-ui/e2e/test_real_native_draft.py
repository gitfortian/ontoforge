"""S0-MVP-0.2 REAL browser + Vite proxy + Spring Boot + PostgreSQL 17.

Requires 127.0.0.1:5173 Vite, 127.0.0.1:8080 Boot, isolated PG17.
Not a mock-page test. Does not claim Windows manual or production auth coverage.
"""
import json
import os
import unittest
import uuid
from pathlib import Path
from urllib.request import Request, urlopen
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:5173"
API = "http://127.0.0.1:8080/api/model/drafts"


def api(path, method="GET", payload=None):
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = Request(API + path, data=body, method=method,
                  headers={"Content-Type": "application/json"})
    with urlopen(req, timeout=12) as response:
        return json.load(response)


class NativeDraftRealBrowserTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.page = self.browser.new_page(viewport={"width": 1370, "height": 900}, locale="zh-CN")
        self.errors = []
        self.page.on("pageerror", lambda e: self.errors.append(str(e)))
        self.page.goto(BASE + "/#/model/packages")
        self.page.get_by_role("heading", name="模型包", exact=True).wait_for()
        self.pkg = "manufacturing.p" + uuid.uuid4().hex

    def tearDown(self):
        if os.getenv("E2E_SHOTS"):
            dest = Path(os.getenv("E2E_SHOTS"))
            dest.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(dest / (self._testMethodName + ".png")), full_page=True)
        self.assertEqual(self.errors, [])
        self.page.close()

    def create_package(self):
        self.page.get_by_role("button", name="创建模型包").click()
        self.page.locator("#create-package-id").fill(self.pkg)
        self.page.get_by_role("button", name="创建并继续").click()
        self.page.locator("#obj-id").wait_for()
        self.id = self.page.url.split("/")[-1]
        self.assertTrue(self.id)

    def edit_type(self):
        self.page.locator("#obj-id").fill("production-batch")
        self.page.locator("#obj-api").fill("ProductionBatch")
        self.page.locator("#obj-label").fill("生产批次")

    def save(self):
        self.page.get_by_role("button", name="保存编辑进度").click()
        self.page.get_by_text("已写入 PostgreSQL", exact=False).wait_for()

    def test_real_pg_wip_refresh_validate_edit_and_409_recovery(self):
        self.create_package()
        self.edit_type()
        self.save()
        self.page.reload()
        self.page.locator("#obj-id").wait_for()
        self.assertEqual(self.page.locator("#obj-id").input_value(), "production-batch")
        self.assertEqual(self.page.locator("#obj-api").input_value(), "ProductionBatch")
        self.page.get_by_role("button", name="检查模型完整性").click()
        self.page.get_by_text("尚未完成", exact=False).first.wait_for()
        self.assertEqual(api("/" + self.id)["validation"]["status"], "INCOMPLETE")
        self.page.locator("#add-property").click()
        self.page.locator("#prop-0-id").fill("batch-id")
        self.page.locator("#prop-0-apiName").fill("batchId")
        self.page.locator("#prop-0-label").fill("批次编号")
        self.page.locator("#prop-0-required").check()
        self.page.locator("#obj-pk").select_option("batch-id")
        self.save()
        self.page.get_by_role("button", name="检查模型完整性").click()
        self.page.get_by_text("完整结构与本阶段语义校验通过", exact=False).wait_for()
        state = api("/" + self.id)
        self.assertEqual(state["validation"]["status"], "VALID")
        self.assertEqual(state["editingContent"]["objectTypes"][0]["primaryKeyRef"], "batch-id")
        self.page.locator("#obj-desc").fill("这次修改尚未保存")
        self.assertTrue(self.page.get_by_role("button", name="检查模型完整性").is_disabled())
        self.save()
        state = api("/" + self.id)
        self.assertEqual(state["validation"]["status"], "NOT_RUN")

        # Simulate a real separate editor saving through the authoritative API.
        remote = state["editingContent"]
        remote["objectTypes"][0]["description"] = "另一标签页提交的更新"
        api("/" + self.id, "PUT", {"expectedRevision": state["revision"], "editingContent": remote})
        self.page.locator("#obj-desc").fill("本地仍需保留的修改")
        self.page.get_by_role("button", name="保存编辑进度").click()
        self.page.get_by_text("DRAFT_REVISION_CONFLICT", exact=False).wait_for()
        self.assertEqual(self.page.locator("#obj-desc").input_value(), "本地仍需保留的修改")
        self.page.get_by_text("另一标签页提交的更新", exact=False).wait_for()
        self.assertEqual(api("/" + self.id)["editingContent"]["objectTypes"][0]["description"],
                         "另一标签页提交的更新")

    def test_p1_slow_post_commit_response_locks_fields_without_losing_edits(self):
        self.create_package()
        self.edit_type()
        self.save()
        self.page.evaluate("""() => {
          const original=window.fetch.bind(window);
          window.fetch=(url,opts)=>opts?.method==='PUT' && String(url).includes('/api/model/drafts/')
            ? new Promise(resolve=>setTimeout(()=>resolve(original(url,opts)),1800))
            : original(url,opts);
        }""")
        self.page.locator("#obj-desc").fill("保存前的输入")
        self.page.get_by_role("button", name="保存编辑进度").click()
        self.assertTrue(self.page.locator("#obj-desc").is_disabled(), "Editor must lock while PUT pending")
        self.assertTrue(self.page.locator("#obj-id").is_disabled())
        self.assertTrue(self.page.locator("#add-property").is_disabled())
        self.assertEqual(self.page.locator("#obj-desc").input_value(), "保存前的输入")
        self.page.get_by_text("已写入 PostgreSQL", exact=False).wait_for(timeout=10000)
        self.assertEqual(self.page.locator("#obj-desc").input_value(), "保存前的输入")
        self.assertTrue(self.page.get_by_role("button", name="保存编辑进度").is_disabled())
        self.page.locator("#obj-desc").fill("响应后新输入")
        self.assertTrue(self.page.get_by_role("button", name="保存编辑进度").is_enabled())
        self.save()
        self.assertEqual(api("/"+self.id)["editingContent"]["objectTypes"][0]["description"], "响应后新输入")

    def test_p1_browser_back_forward_protects_unsaved_draft(self):
        self.create_package()
        self.edit_type()
        self.save()
        self.page.locator("#obj-desc").fill("不可悄悄丢失的本地内容")
        self.page.once("dialog", lambda dialog: dialog.dismiss())
        self.page.go_back(wait_until="domcontentloaded")
        # The hashchange guard restores the URL asynchronously after the
        # browser history transition; go_back() can return before that event.
        self.page.wait_for_function("(hash)=>window.location.hash===hash",
                                    arg="#/model/drafts/"+self.id, timeout=5000)
        self.assertEqual(self.page.locator("#obj-desc").input_value(), "不可悄悄丢失的本地内容")
        # URL history Back has been cancelled; a direct in-app hash nav must
        # pass through the same guard (no silent skip).
        self.page.once("dialog", lambda dialog: dialog.accept())
        self.page.evaluate("window.location.hash='#/model/packages'")
        self.page.get_by_role("heading", name="模型包", exact=True).wait_for()
        self.page.go_back(wait_until="domcontentloaded")
        self.page.locator("#obj-desc").wait_for()
        self.assertEqual(self.page.locator("#obj-desc").input_value(), "")
        self.page.go_forward(wait_until="domcontentloaded")
        self.page.get_by_role("heading", name="模型包", exact=True).wait_for()

    def test_p2_enum_typing_preserves_comma_before_server_save(self):
        self.create_package()
        self.edit_type()
        self.page.locator("#add-property").click()
        self.page.locator("#prop-0-id").fill("quality-status")
        self.page.locator("#prop-0-apiName").fill("qualityStatus")
        self.page.locator("#prop-0-valueType").select_option("ENUM")
        values=self.page.locator("#prop-0-enumValues")
        values.press_sequentially("PLANNED, RUNNING, COMPLETED",delay=12)
        self.assertEqual(values.input_value(),"PLANNED, RUNNING, COMPLETED")
        self.save()
        document=api("/"+self.id)["editingContent"]
        self.assertEqual(document["objectTypes"][0]["properties"][0]["enumValues"],
                         ["PLANNED","RUNNING","COMPLETED"])

    def test_real_api_network_error_never_claims_save_success(self):
        self.create_package()
        self.edit_type()
        self.save()
        self.page.locator("#obj-desc").fill("被断网保护的文本")
        self.page.route("**/api/model/drafts/*",
                        lambda route: route.abort() if route.request.method == "PUT" else route.continue_())
        self.page.get_by_role("button", name="保存编辑进度").click()
        self.page.get_by_text("NETWORK_UNKNOWN", exact=False).wait_for()
        self.assertEqual(self.page.locator("#obj-desc").input_value(), "被断网保护的文本")
        self.page.unroute("**/api/model/drafts/*")
        self.page.get_by_role("button", name="核对服务器最新版").click()
        self.page.get_by_text("服务器仍为 revision", exact=False).wait_for()
        self.save()
        self.assertEqual(api("/" + self.id)["editingContent"]["objectTypes"][0]["description"],
                         "被断网保护的文本")


if __name__ == "__main__":
    unittest.main(verbosity=2)
