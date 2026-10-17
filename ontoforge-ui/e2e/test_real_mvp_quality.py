"""One real vertical golden story: Native schema -> PostgreSQL source binding -> two-hop trace."""
import json
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from playwright.sync_api import sync_playwright

BASE="http://127.0.0.1:5173"
API="http://127.0.0.1:8080/api/mvp/quality"

def call(route,method="GET"):
    try:
        with urlopen(Request(API+route,method=method),timeout=15) as res:
            return res.status,json.load(res)
    except HTTPError as err:
        return err.code,json.load(err)

class QualityMvpRealBrowserTest(unittest.TestCase):
    def test_actual_draft_binding_and_postgres_two_hop_query(self):
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True,args=["--no-sandbox"])
            try:
                page=browser.new_page(viewport={"width":1370,"height":900},locale="zh-CN")
                page.goto(BASE+"/#/mvp/quality")
                page.get_by_role("heading",name="工业质量追溯 · 精简 MVP").wait_for()
                self.assertEqual(call("/trace?batchId=B2026-001")[0],409)
                page.get_by_role("button",name="创建示例本体").click()
                page.get_by_text("VALID 校验通过",exact=False).wait_for(timeout=15000)
                status_code,status=call("/status")
                self.assertEqual(status_code,200)
                self.assertEqual(status["validation"],"VALID")
                self.assertIsNotNone(status["draftId"])
                self.assertFalse(status["bound"])
                page.get_by_role("button",name="绑定示例数据源").click()
                page.get_by_text("绑定已保存 PostgreSQL",exact=False).wait_for()
                page.get_by_role("button",name="查询影响订单").click()
                result=page.get_by_label("真实关联查询结果")
                result.get_by_text("ORD-9001", exact=True).first.wait_for()
                self.assertIn("WO-1001",result.inner_text())
                self.assertIn("mvp_source_batches",result.inner_text())
                self.assertIn("mvp_source_work_orders.work_order_id",result.inner_text())
                code,data=call("/trace?batchId=B2026-001")
                self.assertEqual(code,200)
                self.assertEqual([o["objectTypeId"] for o in data["objects"]],
                                 ["production-batch","work-order","customer-order"])
                self.assertEqual(data["objects"][-1]["id"],"ORD-9001")
                self.assertEqual(data["provenance"],"ISOLATED_SYNTHETIC_POSTGRES")
                self.assertEqual(call("/trace?batchId=B2026-999")[0],404)
                self.assertEqual(call("/trace?batchId=x%27%20OR%201%3D1")[0],400)
                # An edited model revision cannot silently keep the old binding.
                draft_id=status["draftId"]
                req=Request("http://127.0.0.1:8080/api/model/drafts/"+draft_id)
                with urlopen(req,timeout=12) as res:
                    draft=json.load(res)
                content=draft["editingContent"]
                content["objectTypes"][0]["description"]="revision changed after binding"
                update=Request("http://127.0.0.1:8080/api/model/drafts/"+draft_id,
                         method="PUT",
                         headers={"Content-Type":"application/json"},
                         data=json.dumps({"expectedRevision":draft["revision"],
                                          "editingContent":content}).encode())
                with urlopen(update,timeout=12) as res:
                    saved=json.load(res)
                self.assertGreater(saved["revision"],draft["revision"])
                self.assertEqual(call("/trace?batchId=B2026-001")[0],409)
                self.assertFalse(call("/status")[1]["bound"])
                page.reload()
                page.get_by_role("heading",name="工业质量追溯 · 精简 MVP").wait_for()
                self.assertTrue(page.get_by_role("button",name="查询影响订单").is_disabled())
            finally:
                browser.close()
