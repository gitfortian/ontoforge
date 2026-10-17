"""One real vertical golden story: Native schema -> PostgreSQL source binding -> two-hop trace."""
import json
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from playwright.sync_api import sync_playwright
from b0_fixture import headers

BASE="http://127.0.0.1:5173"
API="http://127.0.0.1:8080/api/mvp/quality"

def call(route,method="GET",payload=None):
    data=json.dumps(payload).encode("utf-8") if payload is not None else None
    request=Request(API+route,method=method,data=data,
                    headers={**headers(),"Content-Type":"application/json"})
    try:
        with urlopen(request,timeout=15) as res:
            return res.status,json.load(res)
    except HTTPError as err:
        return err.code,json.load(err)

class QualityMvpRealBrowserTest(unittest.TestCase):
    def test_actual_draft_binding_and_postgres_two_hop_query(self):
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True,args=["--no-sandbox"])
            try:
                page=browser.new_page(viewport={"width":1370,"height":900},locale="zh-CN",extra_http_headers=headers())
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
                page.get_by_role("button",name="保存来源绑定").click()
                page.get_by_text("绑定已保存 PostgreSQL",exact=False).wait_for()
                page.get_by_role("button",name="查询影响订单").click()
                result=page.get_by_label("真实关联查询结果")
                result.get_by_text("ORD-9001", exact=True).first.wait_for()
                self.assertIn("WO-1001",result.inner_text())
                self.assertIn("mvp_source_batches",result.inner_text())
                self.assertIn("mvp_source_work_orders.order_id",result.inner_text())
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
                req=Request("http://127.0.0.1:8080/api/model/drafts/"+draft_id,headers=headers())
                with urlopen(req,timeout=12) as res:
                    draft=json.load(res)
                content=draft["editingContent"]
                content["objectTypes"][0]["description"]="revision changed after binding"
                update=Request("http://127.0.0.1:8080/api/model/drafts/"+draft_id,
                         method="PUT",
                         headers={**headers(),"Content-Type":"application/json"},
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

    def test_z_configure_own_postgres_demo_tables_and_persist(self):
        code,columns=call("/sources")
        self.assertEqual(code,200)
        tables={item["table"]:item["columns"] for item in columns}
        for table in ("demo_quality_lots","demo_prod_jobs","demo_sales_orders"):
            self.assertIn(table,tables)
        self.assertNotIn("native_package_draft",tables)
        status_code,status=call("/status")
        self.assertEqual(status_code,200)
        draft_id=status["draftId"]
        # The preceding golden story changed the draft, so explicitly validate
        # the new revision rather than pretending that the prior VALID remains.
        native=Request("http://127.0.0.1:8080/api/model/drafts/"+draft_id+"/validate",
                       method="POST",headers={**headers(),"Content-Type":"application/json"},
                       data=json.dumps({"expectedRevision":status["revision"]}).encode())
        with urlopen(native,timeout=12) as reply:
            self.assertEqual(json.load(reply)["status"],"VALID")
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True,args=["--no-sandbox"])
            try:
                page=browser.new_page(viewport={"width":1370,"height":900},locale="zh-CN",extra_http_headers=headers())
                page.goto(BASE+"/#/mvp/quality")
                page.get_by_role("button",name="选择 PostgreSQL 表与字段").click()
                config=[
                    ("batchTable","demo_quality_lots"),
                    ("batchId","lot_no"),("batchStatus","outcome"),("batchWorkOrder","job_ref"),
                    ("workTable","demo_prod_jobs"),
                    ("workId","job_no"),("workOrder","sales_ref"),
                    ("orderTable","demo_sales_orders"),
                    ("orderId","sales_no"),("orderName","buyer")
                ]
                for field,value in config:
                    page.locator("#source-"+field).select_option(value)
                # Preview uses the submitted mapping without persisting it.
                expected_map={
                    "batchTable":"demo_quality_lots","batchId":"lot_no",
                    "batchStatus":"outcome","batchWorkOrder":"job_ref",
                    "workTable":"demo_prod_jobs","workId":"job_no",
                    "workOrder":"sales_ref","orderTable":"demo_sales_orders",
                    "orderId":"sales_no","orderName":"buyer"}
                code,preview=call("/preview","POST",expected_map)
                self.assertEqual(code,200)
                self.assertEqual(preview["batches"][0]["id"],"LOT-701")
                self.assertEqual(preview["workOrders"][0]["id"],"JOB-77")
                self.assertEqual(preview["orders"][0]["id"],"SO-88")
                self.assertEqual(preview["linkedExample"]["orderId"],"SO-88")
                # The existing source mapping is unchanged until explicit save.
                before_save=call("/binding")[1]["batchTable"]
                self.assertNotEqual(before_save,"demo_quality_lots")
                page.get_by_role("button",name="预览真实样本与关联").click()
                preview_panel=page.get_by_label("来源预览结果")
                preview_panel.get_by_text("LOT-701",exact=True).first.wait_for()
                self.assertIn("LOT-701 → JOB-77 → SO-88",preview_panel.inner_text())
                self.assertIn("关联值 JOB-77",preview_panel.inner_text())
                self.assertEqual(call("/binding")[1]["batchTable"],before_save)
                # Prevent even a read-only preview from querying native internal tables.
                bad={**expected_map,"orderTable":"native_package_draft"}
                code,err=call("/preview","POST",bad)
                self.assertEqual((code,err["code"]),(422,"SOURCE_SCHEMA_MISMATCH"))
                # Updating a selected field clears the prior preview evidence.
                page.locator("#source-orderName").select_option("sales_no")
                self.assertEqual(preview_panel.count(),0)
                page.locator("#source-orderName").select_option("buyer")
                page.get_by_role("button",name="预览真实样本与关联").click()
                preview_panel.get_by_text("LOT-701",exact=True).first.wait_for()
                page.get_by_role("button",name="保存来源绑定").click()
                page.get_by_text("绑定已保存 PostgreSQL",exact=False).wait_for()
                code,saved=call("/binding")
                self.assertEqual(code,200)
                self.assertEqual(saved["batchTable"],"demo_quality_lots")
                self.assertEqual(saved["orderName"],"buyer")
                page.locator("#mvp-batch-id").fill("LOT-701")
                page.get_by_role("button",name="查询影响订单").click()
                result=page.get_by_label("真实关联查询结果")
                result.get_by_text("SO-88",exact=True).first.wait_for()
                self.assertIn("JOB-77",result.inner_text())
                self.assertIn("demo_prod_jobs.sales_ref",result.inner_text())
                self.assertIn("当前本机 PostgreSQL 测试表",result.inner_text())
                code,actual=call("/trace?batchId=LOT-701")
                self.assertEqual(code,200)
                self.assertEqual(actual["provenance"],"LOCAL_TEST_POSTGRES")
                self.assertEqual(actual["objects"][-1]["id"],"SO-88")
                # A malicious request cannot turn the local MVP into an
                # arbitrary SQL reader of native_package_draft.
                illegal={**saved,"batchTable":"native_package_draft"}
                code,error=call("/bind","POST",illegal)
                self.assertEqual((code,error["code"]),(422,"SOURCE_SCHEMA_MISMATCH"))
                self.assertEqual(call("/binding")[1]["batchTable"],"demo_quality_lots")
                page.reload()
                page.get_by_role("heading",name="工业质量追溯 · 精简 MVP").wait_for()
                page.get_by_role("button",name="选择 PostgreSQL 表与字段").click()
                self.assertEqual(page.locator("#source-batchTable").input_value(),"demo_quality_lots")
                self.assertEqual(page.locator("#source-orderName").input_value(),"buyer")
                self.assertTrue(page.get_by_role("button",name="查询影响订单").is_enabled())
            finally:
                browser.close()

    def test_zz_multi_order_objectset_pagination_in_real_browser(self):
        # Preceding tests leave the fixed Native schema VALID and source mapping persisted.
        code,catalog=call("/sources")
        self.assertEqual(code,200)
        self.assertIn("demo_impact_lots",{r["table"] for r in catalog})
        new_map={
            "batchTable":"demo_impact_lots","batchId":"lot_no",
            "batchStatus":"outcome","batchWorkOrder":"job_ref",
            "workTable":"demo_impact_jobs","workId":"job_no","workOrder":"sales_ref",
            "orderTable":"demo_impact_orders","orderId":"sales_no","orderName":"buyer"}
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True,args=["--no-sandbox"])
            try:
                page=browser.new_page(viewport={"width":1370,"height":900},
                                      locale="zh-CN",extra_http_headers=headers())
                page.goto(BASE+"/#/mvp/quality")
                page.get_by_role("heading",name="工业质量追溯 · 精简 MVP").wait_for()
                page.get_by_role("button",name="选择 PostgreSQL 表与字段").click()
                for key in ("batchTable","workTable","orderTable"):
                    page.locator("#source-"+key).select_option(new_map[key])
                    group=("batchId","batchStatus","batchWorkOrder") if key=="batchTable" else (
                          ("workId","workOrder") if key=="workTable" else ("orderId","orderName"))
                    for field in group:
                        page.locator("#source-"+field).select_option(new_map[field])
                page.get_by_role("button",name="预览真实样本与关联").click()
                page.get_by_label("来源预览结果").get_by_text("LOT-900",exact=True).first.wait_for()
                page.get_by_role("button",name="保存来源绑定").click()
                page.get_by_text("绑定已保存 PostgreSQL",exact=False).wait_for()
                page.locator("#mvp-batch-id").fill("LOT-900")
                page.get_by_role("button",name="查询影响订单").click()
                result=page.get_by_label("真实关联查询结果")
                result.get_by_text("LOT-900 → JOB-70 → SO-91",exact=False).first.wait_for()
                self.assertIn("2",result.inner_text())
                self.assertIn("3",result.inner_text())
                self.assertIn("4",result.inner_text())
                self.assertIn("demo_impact_jobs.sales_ref",result.inner_text())
                self.assertNotIn("JOB-71 → SO-93",result.inner_text())
                result.get_by_role("button",name="下一页").click()
                result.get_by_text("LOT-900 → JOB-71 → SO-93",exact=False).wait_for()
                self.assertIn("第 2 页",result.inner_text())
                result.get_by_role("button",name="上一页").click()
                result.get_by_text("LOT-900 → JOB-70 → SO-91",exact=False).wait_for()
                code,impact=call("/trace-set?batchId=LOT-900&pageSize=2")
                self.assertEqual(code,200)
                self.assertEqual((impact["workOrderCount"],impact["orderCount"],impact["pathCount"]),
                                 (2,3,4))
                self.assertEqual(len(impact["paths"]),2)
                # A missing source linkage is reported, not rewritten as a missing batch.
                code,missing=call("/trace-set?batchId=LOT-903")
                self.assertEqual(code,200)
                self.assertEqual((missing["pathCount"],missing["missingOrderRefs"]),(0,1))
            finally:
                browser.close()

    def test_zzzz_typed_objectset_browse_filters_and_page_in_real_browser(self):
        # The preceding M2 test explicitly binds demo_impact_* in the SAME
        # real PostgreSQL instance; this test proves the consumer can reuse it.
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True,args=["--no-sandbox"])
            try:
                page=browser.new_page(viewport={"width":1370,"height":900},
                                      locale="zh-CN",extra_http_headers=headers())
                page.goto(BASE+"/#/mvp/quality")
                page.get_by_role("heading",name="工业质量追溯 · 精简 MVP").wait_for()
                browser_panel=page.get_by_label("Native 对象集合浏览器")
                browser_panel.get_by_role("heading",name="04 · 浏览本体对象集合").wait_for()
                page.locator("#objectset-type").select_option("customer-order")
                browser_panel.get_by_role("button",name="查询对象集合").click()
                results=page.get_by_label("对象集合查询结果")
                results.get_by_text("SO-91",exact=True).wait_for()
                results.get_by_text("SO-92",exact=True).wait_for()
                self.assertIn("匹配对象 3 个",results.inner_text())
                self.assertIn("demo_impact_orders.buyer",results.inner_text())
                results.get_by_role("button",name="对象下一页").click()
                results.get_by_text("SO-93",exact=True).wait_for()
                self.assertIn("第 2 页",results.inner_text())
                results.get_by_role("button",name="对象上一页").click()
                results.get_by_text("SO-91",exact=True).wait_for()
                page.locator("#objectset-field").select_option("customerName")
                page.locator("#objectset-value").fill("华")
                browser_panel.get_by_role("button",name="查询对象集合").click()
                results.get_by_text("SO-91",exact=True).wait_for()
                self.assertIn("匹配对象 2 个",results.inner_text())
                self.assertNotIn("SO-93",results.inner_text())
                page.locator("#objectset-type").select_option("production-batch")
                page.locator("#objectset-field").select_option("qualityStatus")
                page.locator("#objectset-operator").select_option("eq")
                page.locator("#objectset-value").fill("PASSED")
                browser_panel.get_by_role("button",name="查询对象集合").click()
                results.get_by_text("LOT-901",exact=True).wait_for()
                self.assertIn("匹配对象 1 个",results.inner_text())
                self.assertIn("demo_impact_lots.outcome",results.inner_text())
                code,objects=call("/object-set?objectTypeId=work-order&filterField=id&operator=eq&filterValue=JOB-70")
                self.assertEqual(code,200)
                self.assertEqual(objects["totalCount"],1)
                self.assertEqual(objects["objects"][0]["physicalRowCount"],3)
            finally:
                browser.close()
