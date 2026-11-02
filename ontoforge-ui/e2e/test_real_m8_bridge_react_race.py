"""H07 production React race: the delayed response is from real Spring HTTP.

No mocked ObjectSet response: route.fetch() obtains genuine PG17/JWT data
then postpones browser delivery while the operator changes root or cancels.
"""
import asyncio
import os
import unittest
from pathlib import Path
from urllib.parse import quote

from playwright.async_api import async_playwright, expect, Error as PlaywrightError
from b0_fixture import assigned_package, headers
from test_real_m8_full_workflow import BASE, build_source


class BridgeReactRaceGoldenStory(unittest.IsolatedAsyncioTestCase):
    async def test_stale_real_query_cannot_replace_new_root_after_cancellation(self):
        pkg = assigned_package()
        snapshot, left, _ = await asyncio.to_thread(build_source, pkg)
        errors = []
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True, args=["--no-sandbox"])
            page = await browser.new_page(viewport={"width": 1366, "height": 900},
                                          locale="zh-CN", extra_http_headers=headers())
            page.on("pageerror", lambda error: errors.append(str(error)))
            try:
                await page.goto(BASE + "/#/mvp/object-sets?packageId=" + quote(pkg)
                                + "&bindingId=" + quote(left["bindingId"])
                                + "&bindingRevision=1")
                await page.get_by_role("heading", name="对象集合查询", exact=True).wait_for(
                    timeout=20000)
                panel = page.get_by_role("region", name="关系与一跳遍历 M8")
                await panel.get_by_role("button", name="展开关系工作台").click()
                await panel.get_by_role("button", name="读取当前配置").click()
                await panel.get_by_label("M8 LinkType").locator(
                    'option[value="cell-quality-bridge"]').wait_for(
                        state="attached", timeout=20000)
                await panel.get_by_label("M8 LinkType").select_option("cell-quality-bridge")
                await panel.get_by_label("M8 Bridge Table").select_option("m8_cell_event_bridge")
                await panel.get_by_label("M8 Bridge SOURCE FK").select_option("cell_code")
                await panel.get_by_label("M8 Bridge TARGET FK").select_option("event_code")
                # H08: hold real Spring Boot/PG17 FULL output at browser edge,
                # cancel the first request, edit the draft, then navigate away
                # while a second FULL is pending. Neither late response may
                # re-enable a saved revision or revive validation counters.
                full_events = [asyncio.Event(), asyncio.Event()]
                full_requests = {"count": 0}
                async def delay_real_full(route):
                    full_requests["count"] += 1
                    position = full_requests["count"]
                    try:
                        response = await route.fetch(timeout=25000)
                        if position <= 2:
                            full_events[position - 1].set()
                            await asyncio.sleep(2)
                        await route.fulfill(response=response)
                    except PlaywrightError:
                        pass
                await page.route("**/api/runtime/link-bindings/validate", delay_real_full)
                await panel.get_by_role("tab", name="2 FULL 验证").click()
                await panel.get_by_role("button", name="执行 FULL 验证").click()
                await asyncio.wait_for(full_events[0].wait(), timeout=20)
                await panel.get_by_role("button", name="取消 FULL 校验").click()
                await expect(panel.locator(".m8-validation")).to_have_count(0)
                await expect(panel.get_by_role("button", name="保存 LinkBinding")).to_be_disabled()
                await panel.get_by_label("M8 Bridge SOURCE FK").select_option("event_code")
                await panel.get_by_label("M8 Bridge SOURCE FK").select_option("cell_code")
                await asyncio.sleep(2.25)
                await expect(panel.locator(".m8-validation")).to_have_count(0)
                await expect(panel.get_by_role("button", name="保存 LinkBinding")).to_be_disabled()

                await panel.get_by_role("button", name="执行 FULL 验证").click()
                await asyncio.wait_for(full_events[1].wait(), timeout=20)
                await panel.get_by_role("tab", name="4 一跳查询").click()
                await asyncio.sleep(2.25)
                await expect(panel.locator(".m8-validation")).to_have_count(0)
                await expect(panel.get_by_role("button", name="保存 LinkBinding")).to_be_disabled()
                await page.unroute("**/api/runtime/link-bindings/validate", delay_real_full)
                await panel.get_by_role("tab", name="2 FULL 验证").click()
                await panel.get_by_role("button", name="执行 FULL 验证").click()
                await expect(panel.locator(".m8-validation")).to_contain_text(
                    "VALID_WITH_ORPHANS", timeout=25000)
                await panel.get_by_role("button", name="保存 LinkBinding").click()
                await expect(panel.locator(".m8-current")).to_contain_text(
                    "revision 1", timeout=25000)

                delivered = {"calls": 0, "held": False}
                first_real_response = asyncio.Event()
                cancel_real_response = asyncio.Event()
                async def delay_real_response(route):
                    delivered["calls"] += 1
                    try:
                        response = await route.fetch(timeout=25000)
                        if delivered["calls"] == 1:
                            delivered["held"] = True
                            first_real_response.set()
                            await asyncio.sleep(2)
                        elif delivered["calls"] == 3:
                            cancel_real_response.set()
                            await asyncio.sleep(2)
                        await route.fulfill(response=response)
                    except PlaywrightError:
                        # Browser cancellation may close its intercepted request.
                        # Still never synthesize a successful HTTP response.
                        pass

                await page.route("**/api/runtime/object-sets/traverse", delay_real_response)
                await panel.get_by_label("M8 Root Object Key").fill("CELL-01")
                await panel.get_by_role("button", name="执行一跳查询").click()
                await expect(panel.get_by_role("button", name="取消查询")).to_be_visible(
                    timeout=12000)
                # Do not accidentally cancel before HTTP reaches the backend:
                # wait for a GENUINE server result to be held at the browser edge.
                await asyncio.wait_for(first_real_response.wait(), timeout=20)

                # Root change revokes the old request's presentation rights.
                # This must permit immediately running a different root.
                await panel.get_by_label("M8 Root Object Key").fill("CELL-02")
                await expect(panel.get_by_role("button", name="取消查询")).to_have_count(0)
                await panel.get_by_role("button", name="执行一跳查询").click()
                result = panel.locator(".m8-result")
                await expect(result).to_contain_text("QE-12", timeout=25000)
                await expect(result).not_to_contain_text("QE-11")
                self.assertTrue(delivered["held"], "First request never reached actual server")
                # Wait beyond the first response hold: no stale data/alert can reappear.
                await asyncio.sleep(2.25)
                await expect(result).to_contain_text("QE-12")
                await expect(result).not_to_contain_text("QE-11")
                await expect(panel.locator(".object-set-error")).to_have_count(0)

                # Explicit cancel also removes visible objects, clears busy,
                # and avoids replacing the new root with an obsolete result.
                await panel.get_by_label("M8 Root Object Key").fill("CELL-01")
                await panel.get_by_role("button", name="执行一跳查询").click()
                await asyncio.wait_for(cancel_real_response.wait(), timeout=20)
                await panel.get_by_role("button", name="取消查询").click()
                await expect(result).to_have_count(0)
                await asyncio.sleep(2.25)
                await expect(result).to_have_count(0)
                await panel.get_by_role("button", name="执行一跳查询").click()
                await expect(result).to_contain_text("QE-11", timeout=25000)
                self.assertEqual(errors, [])
                if os.getenv("E2E_SHOTS"):
                    folder = Path(os.environ["E2E_SHOTS"])
                    folder.mkdir(parents=True, exist_ok=True)
                    await page.screenshot(path=str(folder / "m8_bridge_react_h07_race.png"),
                                          full_page=True)
            finally:
                await browser.close()


if __name__ == "__main__":
    unittest.main()
