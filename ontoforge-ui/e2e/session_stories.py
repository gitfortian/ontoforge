"""Run explicitly against the default local-session server; actual password, CSRF and Cookie."""
import json
import os
from pathlib import Path
import unittest
from playwright.sync_api import sync_playwright, expect

CONFIG = json.loads(Path(os.environ['SESSION_E2E_AUTH_FILE']).read_text(encoding='utf-8'))
PACKAGES = iter(CONFIG['packages'])
BASE = 'http://127.0.0.1:5173'


class LocalSessionBrowserTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=['--no-sandbox'])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.context = self.browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
        self.page = self.context.new_page()
        self.errors = []
        self.page.on('pageerror', lambda error: self.errors.append(str(error)))

    def tearDown(self):
        self.assertEqual(self.errors, [])
        self.context.close()

    def login(self):
        self.page.goto(BASE + '/#/model/packages')
        self.page.get_by_role('heading', name='登录 OntoForge', exact=True).wait_for()
        self.submit_login()
        self.page.get_by_role('heading', name='模型包', exact=True).wait_for()

    def submit_login(self):
        self.page.locator('#login-username').fill(CONFIG['username'])
        self.page.locator('#login-password').fill(CONFIG['password'])
        self.page.get_by_role('button', name='登录', exact=True).last.click()

    def open_editor(self):
        self.login()
        self.page.get_by_role('button', name='创建模型包', exact=True).click()
        self.page.locator('#create-package-id').fill(next(PACKAGES))
        self.page.get_by_role('button', name='创建并继续', exact=False).click()
        self.page.locator('#obj-id').wait_for()

    def screenshot(self, name):
        if os.getenv('E2E_SHOTS'):
            directory = Path(os.environ['E2E_SHOTS'])
            directory.mkdir(parents=True, exist_ok=True)
            self.page.screenshot(path=str(directory / name), full_page=True)

    def test_password_login_real_save_refresh_and_server_logout(self):
        self.open_editor()
        self.page.locator('#obj-id').fill('session-object')
        self.page.locator('#obj-api').fill('SessionObject')
        self.page.get_by_role('button', name='保存编辑进度', exact=True).click()
        self.page.get_by_text('当前编辑已与服务器同步', exact=False).wait_for()
        self.page.reload()
        expect(self.page.locator('#obj-id')).to_have_value('session-object')
        expect(self.page.locator('#obj-api')).to_have_value('SessionObject')
        cookies = self.context.cookies()
        self.assertTrue(any(cookie['name'] == 'ONTOFORGE_SESSION' and cookie['httpOnly'] and cookie['sameSite'] == 'Lax' for cookie in cookies))
        self.assertFalse(self.page.evaluate("Object.values(localStorage).some(value => value.includes('password') || value.includes('access_token'))"))
        self.screenshot('session-editor.png')
        self.page.get_by_role('button', name='退出会话', exact=True).click()
        self.page.get_by_role('button', name='Native 本体建模', exact=True).click()
        self.page.get_by_role('heading', name='登录 OntoForge', exact=True).wait_for()
        self.assertEqual(self.page.request.get(BASE + '/api/me').status, 401)

    def test_missing_csrf_and_invalid_password_are_visible(self):
        self.page.goto(BASE + '/#/model/packages')
        self.page.get_by_role('heading', name='登录 OntoForge', exact=True).wait_for()
        self.assertEqual(self.page.request.post(BASE + '/api/auth/login', data={'username': CONFIG['username'], 'password': CONFIG['password']}).status, 403)
        self.page.locator('#login-username').fill(CONFIG['username'])
        self.page.locator('#login-password').fill('incorrect-test-password')
        self.page.get_by_role('button', name='显示密码', exact=True).click()
        expect(self.page.locator('#login-password')).to_have_attribute('type', 'text')
        self.page.get_by_role('button', name='隐藏密码', exact=True).click()
        expect(self.page.locator('#login-password')).to_have_attribute('type', 'password')
        self.page.get_by_role('button', name='登录', exact=True).last.click()
        self.page.get_by_role('alert').filter(has_text='账号或密码不正确').wait_for()
        expect(self.page.locator('#login-password')).to_have_value('')
        self.screenshot('session-login-error.png')

    def test_expired_session_same_account_login_keeps_unsaved_values(self):
        self.open_editor()
        self.page.locator('#obj-id').fill('keep-session-edit')
        # Invalidate through the real logout endpoint in this browser, outside the app state.
        csrf = self.page.request.get(BASE + '/api/auth/context').json()['csrf']
        response = self.page.request.post(BASE + '/api/auth/logout', headers={csrf['headerName']: csrf['token']})
        self.assertEqual(response.status, 204)
        self.page.get_by_role('button', name='保存编辑进度', exact=True).click()
        self.page.get_by_role('dialog').filter(has_text='重新登录').wait_for()
        self.submit_login()
        expect(self.page.get_by_role('dialog')).not_to_be_visible()
        expect(self.page.locator('#obj-id')).to_have_value('keep-session-edit')
        self.page.get_by_text('有未保存的编辑', exact=True).wait_for()

    def test_logout_cancel_preserves_editor_and_cookie(self):
        self.open_editor()
        self.page.locator('#obj-id').fill('cancel-logout-keeps-edit')
        self.page.once('dialog', lambda dialog: dialog.dismiss())
        self.page.get_by_role('button', name='退出会话', exact=True).click()
        expect(self.page.locator('#obj-id')).to_have_value('cancel-logout-keeps-edit')
        self.assertEqual(self.page.request.get(BASE + '/api/me').status, 200)


if __name__ == '__main__':
    unittest.main()
