"""M4 real Runtime Type Catalog browser story over Vite + Spring Boot + PostgreSQL 17."""
import json
import os
import re
import unittest
from pathlib import Path
from urllib.request import Request, urlopen
from playwright.sync_api import sync_playwright
from b0_fixture import headers, assigned_package

BASE = "http://127.0.0.1:5173"
API = "http://127.0.0.1:8080/api/model"


def api(path, method="GET", payload=None):
    request = Request(API + path, data=json.dumps(payload).encode("utf-8") if payload is not None else None,
                      method=method, headers={"Content-Type": "application/json", **headers()})
    with urlopen(request, timeout=12) as response:
        return json.load(response)


class RuntimeTypeRealBrowserTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.page = self.browser.new_page(viewport={"width": 1380, "height": 920}, locale="zh-CN",
                                          extra_http_headers=headers())
        self.errors = []
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))
        self.page.goto(BASE + "/#/model/packages")
        self.page.get_by_role("heading", name="模型包", exact=True).wait_for()
        self.package = assigned_package()

    def tearDown(self):
        if os.getenv("E2E_SHOTS"):
            dest = Path(os.getenv("E2E_SHOTS"))
            dest.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(dest / (self._testMethodName + ".png")), full_page=True)
        self.assertEqual(self.errors, [])
        self.page.close()

    def create_valid_supplier_draft(self):
        page = self.page
        page.get_by_role("button", name="创建模型包").click()
        page.locator("#create-package-id").fill(self.package)
        page.get_by_role("button", name="创建并继续").click()
        page.locator("#obj-id").wait_for()
        draft_id = page.url.split("/")[-1]

        page.locator("#obj-id").fill("supplier-type")
        page.locator("#obj-api").fill("Supplier")
        page.locator("#obj-label").fill("供应商")
        page.locator("#obj-desc").fill("可被采购业务引用的供应商")
        page.locator("#add-property").click()
        page.locator("#prop-0-id").fill("supplier-id")
        page.locator("#prop-0-apiName").fill("supplierId")
        page.locator("#prop-0-label").fill("供应商编号")
        page.locator("#prop-0-valueType").select_option("STRING")
        page.locator("#prop-0-required").check()
        # Keep searchable=false: the resolver must not infer it from primary-key status.
        page.locator("#obj-pk").select_option("supplier-id")
        page.get_by_role("button", name="保存编辑进度").click()
        page.get_by_text("已写入 PostgreSQL", exact=False).wait_for()
        page.get_by_role("button", name="检查模型完整性").click()
        page.get_by_text("完整结构与本阶段语义校验通过", exact=False).wait_for()
        state = api("/drafts/" + draft_id)
        self.assertEqual(state["validation"]["status"], "VALID")
        self.assertEqual(state["validation"]["validatedRevision"], state["revision"])
        return draft_id, state["revision"]

    def test_m4_runtime_catalog_resolves_valid_draft_snapshot_and_switches_sources(self):
        page = self.page
        draft_id, revision = self.create_valid_supplier_draft()

        # The real backend contract and the user-visible entry must agree on the exact revision.
        catalog = api(f"/type-sources/drafts/{draft_id}/types?revision={revision}")
        self.assertEqual(catalog["sourceRef"], {
            "kind": "VALIDATED_DRAFT", "draftId": draft_id, "revision": revision
        })
        self.assertEqual(catalog["packageId"], self.package)
        self.assertEqual(catalog["lifecycle"], "VALIDATED_DRAFT_ONLY")
        self.assertEqual([item["objectTypeId"] for item in catalog["items"]], ["supplier-type"])

        page.get_by_role("button", name="查看运行时类型").click()
        page.get_by_role("heading", name="运行时类型目录").wait_for()
        page.get_by_text("VALIDATED_DRAFT_ONLY", exact=True).wait_for()
        page.locator(".runtime-type-item").get_by_text("供应商", exact=True).click()
        page.get_by_text("供应商编号", exact=True).wait_for()
        self.assertIn("supplier-id", page.locator(".runtime-type-detail").inner_text())
        self.assertIn("可检索", page.locator(".runtime-type-detail").inner_text())
        self.assertIn("不代表已绑定业务实例", page.get_by_text(re.compile("这里只展示类型合同")).inner_text())

        # Return to the draft, freeze its current valid revision, and resolve from the immutable source.
        page.get_by_role("button", name="返回来源").click()
        page.locator("#snapshot-freeze").wait_for()
        page.locator("#snapshot-freeze").click()
        page.get_by_role("dialog", name="确认冻结当前草稿修订").wait_for()
        page.locator("#snapshot-confirm").click()
        page.locator("#snapshot-sha").wait_for(timeout=15000)
        snapshot_id = page.url.split("/")[-1]
        self.assertRegex(snapshot_id, r"^[a-f0-9-]{36}$")
        page.get_by_role("button", name="查看运行时类型").click()
        page.get_by_role("heading", name="运行时类型目录").wait_for()
        page.get_by_text("LOCAL_SNAPSHOT_ONLY", exact=True).wait_for()
        page.locator(".runtime-type-item").get_by_text("供应商", exact=True).click()
        page.locator(".runtime-type-detail").get_by_text(re.compile(r"[0-9a-f]{64}")).wait_for()
        detail = api(f"/type-sources/snapshots/{snapshot_id}/types/supplier-type")
        self.assertEqual(detail["sourceRef"], {"kind": "LOCAL_SNAPSHOT", "snapshotId": snapshot_id})
        self.assertRegex(detail["payloadSha256"], r"^[0-9a-f]{64}$")
        self.assertEqual(detail["lifecycle"], "LOCAL_SNAPSHOT_ONLY")

        # The source selector can switch only to explicit, server-provided sources.
        page.locator("#runtime-source").select_option("draft")
        page.get_by_text("VALIDATED_DRAFT_ONLY", exact=True).wait_for()
        page.locator("#runtime-source").select_option("snapshot:" + snapshot_id)
        page.get_by_text("LOCAL_SNAPSHOT_ONLY", exact=True).wait_for()
        self.assertEqual(api(f"/type-sources/drafts/{draft_id}/types?revision={revision}")["modelSourceRevision"],
                         revision)
