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
from b0_fixture import headers, assigned_package

BASE="http://127.0.0.1:5173"
API="http://127.0.0.1:8080/api/model"

def api(path, method="GET", data=None):
    req=Request(API+path, method=method,
                headers={"Content-Type":"application/json", **headers()},
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
        self.page=self.browser.new_page(viewport={"width":1380,"height":920},locale="zh-CN",
                                        extra_http_headers=headers())
        self.errors=[]
        self.page.on("pageerror",lambda e:self.errors.append(str(e)))
        self.page.goto(BASE+"/#/model/packages")
        self.page.get_by_role("heading",name="模型包",exact=True).wait_for()
        self.pkg=assigned_package()
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
        self.assertIn("不提供",self.page.locator(".model-alert.warning").last.inner_text(),
                      "Diff must explicitly deny compatibility certification")
        status,old=api("/snapshots/"+sid1)
        self.assertEqual(old["payloadSha256"],digest)
        self.assertEqual(old["snapshotContent"]["objectTypes"][0]["description"],"批次可追溯")
        self.assertEqual(api("/packages/"+self.pkg+"/snapshots")[1]["items"].__len__(),2)
    def test_s03_server_commits_but_browser_loses_reply_recovers_same_snapshot(self):
        self.prepare()
        done=[]
        # Commit the intercepted request through a second connection, then
        # drop ONLY the UI-visible response to exercise RESULT_UNKNOWN.
        def commit_then_abort(route):
            if route.request.method=="POST":
                request_body=route.request.post_data_json
                status,result=api("/drafts/"+self.draft+"/snapshots","POST",request_body)
                self.assertEqual(status,201)
                done.append(result["snapshotId"])
                route.abort("failed")
            else:
                route.continue_()
        self.page.route("**/api/model/drafts/*/snapshots",commit_then_abort)
        self.page.locator("#snapshot-freeze").click()
        self.page.locator("#snapshot-confirm").click()
        self.page.get_by_text("RESULT_UNKNOWN",exact=False).first.wait_for(timeout=12000)
        self.assertEqual(len(done),1)
        self.assertEqual(len(api("/packages/"+self.pkg+"/snapshots")[1]["items"]),1)
        self.page.unroute("**/api/model/drafts/*/snapshots")
        self.page.locator("#snapshot-reconcile").click()
        self.page.locator("#snapshot-sha").wait_for(timeout=14000)
        self.assertEqual(self.page.url.split("/")[-1],done[0])
        self.assertEqual(len(api("/packages/"+self.pkg+"/snapshots")[1]["items"]),1)

    def test_s03_unknown_freeze_refresh_preserves_original_key(self):
        self.prepare()
        self.page.route("**/api/model/drafts/*/snapshots",
                         lambda route:route.abort() if route.request.method=="POST" else route.continue_())
        self.page.locator("#snapshot-freeze").click()
        self.page.locator("#snapshot-confirm").click()
        self.page.get_by_text("RESULT_UNKNOWN",exact=False).first.wait_for()
        self.assertTrue(self.page.locator("#snapshot-reconcile").is_visible())
        key_before=self.page.evaluate("""() => Object.keys(sessionStorage).filter(
          k=>k.startsWith('ontoforge-local-snapshot-pending:'))[0]""")
        self.assertTrue(key_before)
        self.page.unroute("**/api/model/drafts/*/snapshots")
        self.page.reload()
        self.page.locator("#snapshot-reconcile").wait_for(timeout=10000)
        self.assertTrue(self.page.locator("#snapshot-freeze").is_disabled())
        self.assertEqual(self.page.evaluate("""() => Object.keys(sessionStorage).filter(
          k=>k.startsWith('ontoforge-local-snapshot-pending:'))[0]"""),key_before)
        self.page.get_by_role("button",name=re.compile("使用原键重试")).click()
        self.page.locator("#snapshot-sha").wait_for(timeout=12000)
        self.assertEqual(len(api("/packages/"+self.pkg+"/snapshots")[1]["items"]),1)
        self.assertIsNone(self.page.evaluate("(id) => sessionStorage.getItem('ontoforge-local-snapshot-pending:'+id)",self.draft))

    def test_s03_history_paging_keeps_compare_choices_across_pages(self):
        # Real PG17 + Java: 22 immutable revisions make two keyset pages.
        # The browser must preserve earlier choices after loading more.
        self.prepare()
        snapshots=[]
        for revision in range(2,24):
            if revision>2:
                _,draft=api("/drafts/"+self.draft)
                content=draft["editingContent"]
                content["objectTypes"][0]["description"]="Batch release candidate "+str(revision)
                status,updated=api("/drafts/"+self.draft,"PUT",
                    {"expectedRevision":revision-1,"editingContent":content})
                self.assertEqual(status,200)
                self.assertEqual(updated["revision"],revision)
                status,report=api("/drafts/"+self.draft+"/validate","POST",
                    {"expectedRevision":revision})
                self.assertEqual(status,200)
                self.assertEqual(report["status"],"VALID")
            status,frozen=api("/drafts/"+self.draft+"/snapshots","POST",
                {"expectedRevision":revision,"idempotencyKey":str(uuid.uuid4())})
            self.assertEqual(status,201)
            snapshots.append(frozen["snapshotId"])
        self.page.evaluate("(pkg)=>{window.location.hash='#/model/packages/'+pkg+'/snapshots'}",self.pkg)
        self.page.get_by_role("heading",name="历史模型快照").wait_for()
        self.page.locator("#snapshot-from").wait_for(timeout=12000)
        self.assertEqual(self.page.locator("#snapshot-from option").count(),20)
        self.page.get_by_role("button",name="加载更多历史快照").click()
        self.page.wait_for_function(
            "() => document.querySelectorAll('#snapshot-from option').length===22",timeout=20000)
        self.page.locator("#snapshot-from").select_option(snapshots[0])
        self.page.locator("#snapshot-to").select_option(snapshots[-1])
        self.page.locator("#snapshot-compare").click()
        self.page.locator("#snapshot-diff-table").wait_for()
        self.assertIn("说明变更",self.page.locator("#snapshot-diff-table").inner_text())
        self.assertEqual(len(api("/packages/"+self.pkg+"/snapshots?pageSize=50")[1]["items"]),22)

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

    def test_s04_real_three_objects_two_links_reopen_and_compare(self):
        self.prepare()
        p=self.page
        for oid,api_name,title in [("work-order","WorkOrder","生产工单"),
                                    ("customer-order","CustomerOrder","销售订单")]:
            p.get_by_role("button",name="新增对象").click()
            p.locator("#obj-id").fill(oid)
            p.locator("#obj-api").fill(api_name)
            p.locator("#obj-label").fill(title)
            p.locator("#add-property").click()
            p.locator("#prop-0-id").fill("business-id")
            p.locator("#prop-0-apiName").fill("businessId")
            p.locator("#prop-0-required").check()
            p.locator("#obj-pk").select_option("business-id")
        p.get_by_role("tab",name=re.compile("关系类型")).click()
        for rid,api_name,title,source,target,card in [
            ("batch-to-workorder","BatchToWorkOrder","批次归属工单",
             "production-batch","work-order","MANY_TO_ONE"),
            ("workorder-to-order","WorkOrderToOrder","工单关联订单",
             "work-order","customer-order","MANY_TO_MANY")]:
            p.get_by_role("button",name="新增关系").click()
            p.locator("#link-id").fill(rid)
            p.locator("#link-api").fill(api_name)
            p.locator("#link-label").fill(title)
            p.locator("#link-source").select_option(source)
            p.locator("#link-target").select_option(target)
            p.locator("#link-cardinality").select_option(card)
        self.save_validate()
        self.assertEqual(api("/drafts/"+self.draft)[1]["editingContent"]["linkTypes"].__len__(),2)
        p.reload()
        p.get_by_role("tab",name=re.compile("关系类型")).click()
        self.assertEqual(p.locator(".model-type-choices button").count(),2)
        first=self.freeze()
        body=api("/snapshots/"+first)[1]
        self.assertEqual(len(body["snapshotContent"]["objectTypes"]),3)
        self.assertEqual(len(body["snapshotContent"]["linkTypes"]),2)
        first_digest=body["payloadSha256"]
        self.back_editor()
        p.get_by_role("tab",name=re.compile("关系类型")).click()
        p.get_by_role("button",name="批次归属工单").click()
        p.locator("#link-cardinality").select_option("ONE_TO_ONE")
        p.locator("#link-evidence").select_option("false")
        self.save_validate()
        second=self.freeze()
        data=api("/snapshot-diffs?from="+first+"&to="+second)[1]
        self.assertIn("LINK_TYPE",json.dumps(data["changes"]))
        self.assertIn("POLICY_REVIEW_REQUIRED",json.dumps(data["changes"]))
        self.assertIn("POTENTIALLY_BREAKING",json.dumps(data["changes"]))
        self.assertEqual(api("/snapshots/"+first)[1]["payloadSha256"],first_digest)

    def test_s04_missing_link_target_wip_and_invalid_reference_preserve_data(self):
        self.prepare()
        p=self.page
        p.get_by_role("tab",name=re.compile("关系类型")).click()
        p.get_by_role("button",name="新增关系").click()
        p.locator("#link-id").fill("batch-to-missing")
        p.locator("#link-api").fill("BatchToMissing")
        p.locator("#link-source").select_option("production-batch")
        self.page.get_by_role("button",name="保存编辑进度").click()
        p.get_by_text("已写入 PostgreSQL",exact=False).wait_for()
        p.get_by_role("button",name="检查模型完整性").click()
        p.get_by_text("校验完成",exact=False).wait_for()
        self.assertEqual(api("/drafts/"+self.draft)[1]["editingContent"]["linkTypes"].__len__(),1)
        self.assertTrue(p.locator("#snapshot-freeze").is_disabled())
        assert p.locator(".model-issue-link").count()>0

    def test_s04_delete_referenced_object_keeps_dangling_link_for_manual_repair(self):
        self.prepare()
        p=self.page
        p.get_by_role("button",name="新增对象").click()
        p.locator("#obj-id").fill("work-order")
        p.locator("#obj-api").fill("WorkOrder")
        p.locator("#obj-label").fill("生产工单")
        p.locator("#add-property").click()
        p.locator("#prop-0-id").fill("workorder-id")
        p.locator("#prop-0-apiName").fill("workOrderId")
        p.locator("#prop-0-required").check()
        p.locator("#obj-pk").select_option("workorder-id")
        p.get_by_role("tab",name=re.compile("关系类型")).click()
        p.get_by_role("button",name="新增关系").click()
        p.locator("#link-id").fill("batch-to-workorder")
        p.locator("#link-api").fill("BatchToWorkOrder")
        p.locator("#link-label").fill("生产批次归属工单")
        p.locator("#link-source").select_option("production-batch")
        p.locator("#link-target").select_option("work-order")
        p.locator("#link-cardinality").select_option("MANY_TO_ONE")
        self.save_validate()
        p.get_by_role("tab",name=re.compile("对象类型")).click()
        p.locator(".model-type-choices button").filter(has_text="生产工单").click()
        p.once("dialog",lambda dialog:dialog.accept())
        p.get_by_role("button",name=re.compile("删除当前对象")).click()
        p.get_by_role("tab",name=re.compile("关系类型")).click()
        self.assertEqual(p.locator("#link-target").input_value(),"work-order")
        self.assertIn("已不存在",p.locator("#link-target").inner_text())
        self.assertIn("原始引用未被自动改写",p.locator("#view, .model-main-column").first.inner_text())
        p.get_by_role("button",name="保存编辑进度").click()
        p.get_by_text("已写入 PostgreSQL",exact=False).wait_for()
        p.get_by_role("button",name="检查模型完整性").click()
        p.get_by_text("校验完成",exact=False).wait_for()
        doc=api("/drafts/"+self.draft)[1]["editingContent"]
        self.assertEqual(len(doc["objectTypes"]),1)
        self.assertEqual(len(doc["linkTypes"]),1)
        self.assertEqual(doc["linkTypes"][0]["targetTypeRef"],"work-order")
        self.assertTrue(p.locator("#snapshot-freeze").is_disabled())
        p.locator(".model-issue-link").filter(has_text="关联对象在当前包中不存在").click()
        self.assertTrue(p.locator("#link-target").is_visible())

if __name__=="__main__":
    unittest.main(verbosity=2)
