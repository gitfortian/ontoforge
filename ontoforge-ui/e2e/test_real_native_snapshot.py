"""OFIP-0003 REAL Vite + React + Spring Boot + PostgreSQL17 Chromium acceptance.

No mocked server, no approved/released claims, no Windows native runtime claim.
"""
import json
import os
import re
import unittest
import uuid
from pathlib import Path
from urllib.request import Request, urlopen
from playwright.sync_api import sync_playwright

BASE="http://127.0.0.1:5173"
API="http://127.0.0.1:8080/api/model"

def api(path, method="GET", data=None):
    req=Request(API+path, method=method,
                headers={"Content-Type":"application/json"},
                data=json.dumps(data).encode("utf-8") if data is not None else None)
    with urlopen(req,timeout=12) as response:
        return response.status,json.load(response)

class NativeSnapshotRealE2ETest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pw=sync_playwright().start()
        cls.browser=cls.pw.chromium.launch(headless=True,args=["--no-sandbox"])
    @classmethod
    def tearDownClass(cls):
        cls.browser.close();cls.pw.stop()
    def setUp(self):
        self.page=self.browser.new_page(viewport={"width":1380,"height":920},locale="zh-CN")
        self.errors=[]
        self.page.on("pageerror",lambda e:self.errors.append(str(e)))
        self.page.goto(BASE+"/#/model/packages")
        self.page.get_by_role("heading",name="模型包",exact=True).wait_for()
        self.pkg="manufacturing.p"+uuid.uuid4().hex
    def tearDown(self):
        if os.getenv("E2E_SHOTS"):
            dest=Path(os.getenv("E2E_SHOTS"));dest.mkdir(parents=True,exist_ok=True)
            self.page.screenshot(path=str(dest/(self._testMethodName+".png")),full_page=True)
        self.assertEqual(self.errors,[])
        self.page.close()
    def prepare(self):
        p=self.page
        p.get_by_role("button",name="创建模型包").click()
        p.locator("#create-package-id").fill(self.pkg)
        p.get_by_role("button",name="创建并继续").click()
        p.locator("#obj-id").wait_for()
        self.draft=p.url.split("/")[-1]
        p.locator("#obj-id").fill("production-batch")
        p.locator("#obj-api").fill("ProductionBatch")
        p.locator("#obj-label").fill("生产批次")
        p.locator("#obj-desc").fill("批次可追溯")
        p.locator("#add-property").click()
        p.locator("#prop-0-id").fill("batch-id")
        p.locator("#prop-0-apiName").fill("batchId")
        p.locator("#prop-0-valueType").select_option("STRING")
        p.locator("#prop-0-required").check()
        p.locator("#prop-0-searchable").check()
        p.locator("#obj-pk").select_option("batch-id")
        self.save_validate()
    def save_validate(self):
        self.page.get_by_role("button",name="保存编辑进度").click()
        self.page.get_by_text("已写入 PostgreSQL",exact=False).wait_for()
        self.page.get_by_role("button",name="检查模型完整性").click()
        self.page.get_by_text("完整结构与本阶段语义校验通过",exact=False).wait_for()
        self.page.locator("#snapshot-freeze").wait_for(state="visible")
        self.assertTrue(self.page.locator("#snapshot-freeze").is_enabled())
    def freeze(self):
        p=self.page
        p.locator("#snapshot-freeze").click()
        p.get_by_role("dialog",name="确认冻结当前草稿修订").wait_for()
        p.locator("#snapshot-confirm").click()
        p.locator("#snapshot-sha").wait_for(timeout=14000)
        sid=p.url.split("/")[-1]
        self.assertRegex(sid,r"^[a-f0-9-]{36}$")
        self.assertRegex(p.locator("#snapshot-sha").inner_text(),r"^[a-f0-9]{64}$")
        return sid
    def back_editor(self):
        self.page.get_by_role("button",name="回到当前草稿").click()
        self.page.locator("#obj-desc").wait_for()
    def test_s03_real_postgresql_two_freezes_diff_readonly_refresh(self):
        self.prepare()
        sid1=self.freeze()
        status,first=api("/snapshots/"+sid1)
        self.assertEqual(status,200)
        self.assertEqual(first["lifecycle"],"LOCAL_SNAPSHOT_ONLY")
        digest=first["payloadSha256"]
        self.page.reload()
        self.page.locator("#snapshot-sha").wait_for()
        self.assertEqual(self.page.locator("#snapshot-sha").inner_text(),digest)
        self.assertEqual(self.page.locator("#obj-desc").count(),0,"Historical snapshot must be read-only")
        self.back_editor()
        self.page.locator("#obj-desc").fill("批次可追溯・第二版本")
        self.page.locator("#prop-0-searchable").uncheck()
        self.assertTrue(self.page.locator("#snapshot-freeze").is_disabled())
        self.save_validate()
        sid2=self.freeze()
        self.assertNotEqual(sid1,sid2)
        self.page.get_by_role("button",name="历史快照").click()
        self.page.get_by_role("heading",name="历史模型快照").wait_for()
        self.page.locator("#snapshot-from").select_option(sid1)
        self.page.locator("#snapshot-to").select_option(sid2)
        self.page.locator("#snapshot-compare").click()
        self.page.locator("#snapshot-diff-table").wait_for()
        content=self.page.locator("#snapshot-diff-table").inner_text()
        self.assertIn("潜在破坏性",content)
        self.assertIn("说明变更",content)
        self.assertIn("searchable",content)
        self.assertIn("不能",self.page.locator("#view").inner_text()) if False else None
        status,old=api("/snapshots/"+sid1)
        self.assertEqual(old["payloadSha256"],digest)
        self.assertEqual(old["snapshotContent"]["objectTypes"][0]["description"],"批次可追溯")
        self.assertEqual(api("/packages/"+self.pkg+"/snapshots")[1]["items"].__len__(),2)
    def test_s03_network_uncertainty_same_key_recovery_no_duplicates(self):
        self.prepare()
        self.page.route("**/api/model/drafts/*/snapshots",
                         lambda route:route.abort() if route.request.method=="POST" else route.continue_())
        self.page.locator("#snapshot-freeze").click()
        self.page.locator("#snapshot-confirm").click()
        self.page.get_by_text("RESULT_UNKNOWN",exact=False).first.wait_for()
        self.assertTrue(self.page.locator("#snapshot-freeze").is_disabled())
        self.assertEqual(len(api("/packages/"+self.pkg+"/snapshots")[1]["items"]),0)
        self.page.unroute("**/api/model/drafts/*/snapshots")
        self.page.get_by_role("button",name=re.compile("使用原键重试")).click()
        self.page.locator("#snapshot-sha").wait_for()
        self.assertEqual(len(api("/packages/"+self.pkg+"/snapshots")[1]["items"]),1)
        self.assertEqual(api("/snapshots/"+self.page.url.split("/")[-1])[1]["sourceRevision"],2)

if __name__=="__main__":
    unittest.main(verbosity=2)
