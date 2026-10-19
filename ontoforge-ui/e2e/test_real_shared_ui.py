"""Shared Yak UI and theme behavior over the actual API, app and browser."""
import os
from pathlib import Path
import unittest

from playwright.sync_api import sync_playwright, expect
from b0_fixture import headers, assigned_package

BASE = 'http://127.0.0.1:5173'


class SharedUiRealBrowserTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=['--no-sandbox'])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.page = self.browser.new_page(viewport={'width': 1440, 'height': 1000},
                                          locale='zh-CN', extra_http_headers=headers())
        self.errors = []
        self.page.on('pageerror', lambda error: self.errors.append(str(error)))

    def tearDown(self):
        self.assertEqual(self.errors, [])
        self.page.close()

    def open_app(self):
        self.page.goto(BASE + '/#/system')
        self.page.get_by_role('heading', name='系统概览', exact=True).wait_for()

    def open_appearance(self):
        self.page.get_by_role('button', name='界面设置', exact=True).click()
        self.page.get_by_role('dialog').wait_for()

    def screenshot(self, name):
        if os.getenv('E2E_SHOTS'):
            directory = Path(os.environ['E2E_SHOTS'])
            directory.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(directory / name), full_page=True)

    def test_dialog_keyboard_theme_persistence_and_mobile_navigation(self):
        self.open_app()
        self.screenshot('shared-ui-light.png')
        self.open_appearance()
        for _ in range(5):
            self.page.keyboard.press('Tab')
            # Base UI's focus guard redirects on the next browser task.
            self.page.wait_for_function("Boolean(document.activeElement.closest('[role=dialog]'))")
            self.assertTrue(self.page.evaluate("Boolean(document.activeElement.closest('[role=dialog]'))"))
        self.page.locator('#appearance-theme').select_option('dark')
        expect(self.page.locator('html')).to_have_attribute('data-theme', 'dark')
        self.page.keyboard.press('Escape')
        expect(self.page.get_by_role('dialog')).not_to_be_visible()
        expect(self.page.get_by_role('button', name='界面设置', exact=True)).to_be_focused()
        self.page.reload()
        expect(self.page.locator('html')).to_have_attribute('data-theme', 'dark')
        self.page.get_by_role('heading', name='系统概览', exact=True).wait_for()
        self.screenshot('shared-ui-dark.png')
        self.page.set_viewport_size({'width': 390, 'height': 844})
        self.page.get_by_role('button', name='Native 本体建模', exact=True).click()
        self.page.get_by_role('heading', name='模型包', exact=True).wait_for()
        self.assertTrue(self.page.get_by_role('button', name='系统概览', exact=True).is_visible())
        self.assertLessEqual(self.page.evaluate('document.documentElement.scrollWidth'), 390)
        self.screenshot('shared-ui-mobile.png')

    def test_system_theme_changes_live_and_explicit_choice_overrides_it(self):
        self.open_app()
        self.open_appearance()
        self.page.locator('#appearance-theme').select_option('system')
        self.page.emulate_media(color_scheme='dark')
        expect(self.page.locator('html')).to_have_attribute('data-theme', 'dark')
        self.page.emulate_media(color_scheme='light')
        expect(self.page.locator('html')).to_have_attribute('data-theme', 'light')
        self.page.locator('#appearance-theme').select_option('dark')
        self.page.emulate_media(color_scheme='light')
        expect(self.page.locator('html')).to_have_attribute('data-theme', 'dark')

    def test_theme_change_preserves_unsaved_native_editor_values(self):
        self.open_app()
        self.page.get_by_role('button', name='Native 本体建模', exact=True).click()
        self.page.get_by_role('heading', name='模型包', exact=True).wait_for()
        self.page.get_by_role('button', name='创建模型包', exact=True).click()
        self.page.locator('#create-package-id').fill(assigned_package())
        self.page.get_by_role('button', name='创建并继续', exact=False).click()
        self.page.locator('#obj-id').wait_for()
        self.page.locator('#obj-id').fill('theme-keeps-edit')
        self.page.locator('#obj-api').fill('ThemeKeepsEdit')
        self.open_appearance()
        self.page.locator('#appearance-theme').select_option('dark')
        self.page.get_by_role('button', name='完成', exact=True).click()
        expect(self.page.locator('#obj-id')).to_have_value('theme-keeps-edit')
        expect(self.page.locator('#obj-api')).to_have_value('ThemeKeepsEdit')
        self.page.get_by_text('有未保存的编辑', exact=True).wait_for()
        self.assertNotEqual(self.page.locator('.model-panel').first.evaluate(
            'element => getComputedStyle(element).backgroundColor'), 'rgb(255, 255, 255)')
        self.assertEqual(self.page.locator('.model-sticky').evaluate(
            'element => getComputedStyle(element).backgroundColor'), self.page.locator('.app-shell').evaluate(
            'element => getComputedStyle(element).backgroundColor'))
        self.screenshot('shared-ui-editor-dark.png')

    def test_blocked_browser_storage_keeps_theme_usable(self):
        self.page.add_init_script("""Storage.prototype.getItem = function() { throw new DOMException('blocked', 'SecurityError'); };
            Storage.prototype.setItem = function() { throw new DOMException('blocked', 'SecurityError'); };""")
        self.open_app()
        self.open_appearance()
        self.page.locator('#appearance-theme').select_option('dark')
        expect(self.page.locator('html')).to_have_attribute('data-theme', 'dark')
        self.page.get_by_role('alert').filter(has_text='浏览器不允许保存偏好').wait_for()


if __name__ == '__main__':
    unittest.main()
