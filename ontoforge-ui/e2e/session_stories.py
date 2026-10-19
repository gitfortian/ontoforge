"""Run explicitly against the default local-session server; actual password, CSRF and Cookie."""
import json
import os
from pathlib import Path
import unittest
import uuid
from playwright.sync_api import sync_playwright, expect

CONFIG = json.loads(Path(os.environ['SESSION_E2E_AUTH_FILE']).read_text(encoding='utf-8'))
PACKAGES = iter(CONFIG['packages'])
BASE = 'http://127.0.0.1:5173'


class SessionBrowserCase(unittest.TestCase):
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
        # This account may own additional spaces created by other stories. Select the explicit fixture grant.
        self.page.locator('#current-workspace').select_option(CONFIG['workspaceId'])
        self.page.get_by_role('button', name='Native 本体建模', exact=True).click()

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

class LocalSessionBrowserTest(SessionBrowserCase):
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


class AccountManagementBrowserTest(SessionBrowserCase):
    def create_user(self, system_admin=False):
        self.login()
        username = 'managed.' + uuid.uuid4().hex
        password = 'test-account-' + uuid.uuid4().hex
        csrf = self.page.request.get(BASE + '/api/auth/context').json()['csrf']
        response = self.page.request.post(BASE + '/api/platform/accounts', headers={csrf['headerName']: csrf['token']},
            data={'username': username, 'displayName': '浏览器普通账号', 'password': password, 'systemAdmin': system_admin})
        self.assertEqual(response.status, 201)
        return username, password

    def login_page(self, page, username, password):
        page.goto(BASE + '/#/model/packages')
        page.get_by_role('heading', name='登录 OntoForge', exact=True).wait_for()
        page.locator('#login-username').fill(username)
        page.locator('#login-password').fill(password)
        page.get_by_role('button', name='登录', exact=True).last.click()
        page.get_by_role('heading', name='模型包', exact=True).wait_for()

    def test_admin_ui_create_disable_reset_enable_and_new_password_login(self):
        self.login()
        self.page.get_by_role('button', name='账号管理', exact=True).click()
        self.page.get_by_role('button', name='新增账号', exact=True).click()
        username = 'browser.' + uuid.uuid4().hex
        self.page.locator('#account-username').fill(username)
        self.page.locator('#account-display-name').fill('真实浏览器账号')
        self.page.locator('#account-password').fill('initial-' + uuid.uuid4().hex)
        self.page.get_by_role('button', name='保存', exact=True).click()
        expect(self.page.get_by_role('dialog')).not_to_be_visible()
        self.page.locator('#account-search').fill(username)
        self.page.get_by_role('button', name='查询', exact=True).click()
        row = self.page.get_by_role('row').filter(has_text=username)
        expect(row).to_contain_text('普通用户')
        self.page.get_by_role('button', name='编辑 ' + username, exact=True).click()
        self.page.locator('#account-display-name').fill('已编辑的账号')
        self.page.get_by_label('启用账号', exact=True).uncheck()
        self.page.get_by_role('button', name='保存', exact=True).click()
        expect(row).to_contain_text('停用')
        self.page.get_by_role('button', name='重置 ' + username + ' 的密码', exact=True).click()
        replacement = 'replacement-' + uuid.uuid4().hex
        self.page.locator('#account-password').fill(replacement)
        self.page.get_by_role('button', name='保存', exact=True).click()
        expect(self.page.get_by_role('dialog')).not_to_be_visible()
        self.page.get_by_role('button', name='编辑 ' + username, exact=True).click()
        self.page.get_by_label('启用账号', exact=True).check()
        self.page.get_by_role('button', name='保存', exact=True).click()
        expect(row).to_contain_text('已编辑的账号')
        expect(row).to_contain_text('启用')
        self.page.set_viewport_size({'width': 390, 'height': 844})
        self.assertLessEqual(self.page.evaluate('document.documentElement.scrollWidth'), 390)
        self.screenshot('account-admin-mobile.png')
        other = self.browser.new_context()
        try:
            self.login_page(other.new_page(), username, replacement)
        finally:
            other.close()

    def test_normal_user_has_no_admin_entry_and_direct_api_is_forbidden(self):
        username, password = self.create_user()
        other = self.browser.new_context()
        try:
            page = other.new_page()
            self.login_page(page, username, password)
            self.assertEqual(page.get_by_role('button', name='账号管理', exact=True).count(), 0)
            self.assertEqual(page.request.get(BASE + '/api/platform/accounts').status, 403)
            page.goto(BASE + '/#/platform/accounts')
            page.get_by_role('alert').filter(has_text='无权').wait_for()
            self.assertEqual(page.get_by_role('button', name='新增账号', exact=True).count(), 0)
        finally:
            other.close()

    def test_user_changes_own_password_and_other_cookie_is_revoked(self):
        username, password = self.create_user()
        one, two = self.browser.new_context(), self.browser.new_context()
        try:
            page = one.new_page()
            self.login_page(page, username, password)
            self.login_page(two.new_page(), username, password)
            page.get_by_role('button', name='修改密码', exact=True).click()
            page.locator('#current-password').fill(password)
            replacement = 'new-personal-' + uuid.uuid4().hex
            page.locator('#new-password').fill(replacement)
            page.locator('#confirm-password').fill(replacement)
            page.get_by_role('button', name='修改并重新登录', exact=True).click()
            page.get_by_role('heading', name='重新登录', exact=True).wait_for()
            self.assertEqual(two.request.get(BASE + '/api/me').status, 401)
            page.locator('#login-username').fill(username)
            page.locator('#login-password').fill(replacement)
            page.get_by_role('button', name='登录', exact=True).last.click()
            expect(page.get_by_role('dialog')).not_to_be_visible()
            self.assertEqual(page.request.get(BASE + '/api/me').status, 200)
        finally:
            one.close()
            two.close()

    def test_admin_demotion_clears_cached_account_table_after_relogin(self):
        username, password = self.create_user(system_admin=True)
        # A peer administrator performs the demotion; the original test administrator remains unchanged.
        other_name = 'peer.' + uuid.uuid4().hex
        other_password = 'peer-password-' + uuid.uuid4().hex
        csrf = self.page.request.get(BASE + '/api/auth/context').json()['csrf']
        response = self.page.request.post(BASE + '/api/platform/accounts', headers={csrf['headerName']: csrf['token']},
            data={'username': other_name, 'displayName': 'Peer Admin', 'password': other_password, 'systemAdmin': True})
        self.assertEqual(response.status, 201)
        one, two = self.browser.new_context(), self.browser.new_context()
        try:
            page, peer_page = one.new_page(), two.new_page()
            self.login_page(page, username, password)
            self.login_page(peer_page, other_name, other_password)
            page.get_by_role('button', name='账号管理', exact=True).click()
            page.get_by_role('button', name='新增账号', exact=True).wait_for()
            account = peer_page.request.get(BASE + '/api/platform/accounts?q=' + username).json()['items'][0]
            token = peer_page.request.get(BASE + '/api/auth/context').json()['csrf']
            demoted = peer_page.request.put(BASE + '/api/platform/accounts/' + account['accountId'],
                headers={token['headerName']: token['token']}, data={'expectedVersion': account['version'],
                    'displayName': account['displayName'], 'enabled': True, 'systemAdmin': False})
            self.assertEqual(demoted.status, 200)
            page.get_by_role('button', name='刷新', exact=True).click()
            page.get_by_role('heading', name='重新登录', exact=True).wait_for()
            page.locator('#login-username').fill(username)
            page.locator('#login-password').fill(password)
            page.get_by_role('button', name='登录', exact=True).last.click()
            page.get_by_role('alert').filter(has_text='无权').wait_for()
            self.assertEqual(page.get_by_role('row').count(), 0)
            self.assertEqual(page.get_by_role('button', name='账号管理', exact=True).count(), 0)
        finally:
            one.close()
            two.close()


class WorkspaceBrowserTest(SessionBrowserCase):
    def mutate(self, request, method, path, data=None):
        csrf = request.get(BASE + '/api/auth/context').json()['csrf']
        return getattr(request, method)(BASE + path, headers={csrf['headerName']: csrf['token']}, **({'data': data} if data is not None else {}))

    def account(self):
        username, password = 'space.' + uuid.uuid4().hex, 'space-password-' + uuid.uuid4().hex
        response = self.mutate(self.page.request, 'post', '/api/platform/accounts', {'username': username, 'displayName': username, 'password': password, 'systemAdmin': False})
        self.assertEqual(response.status, 201)
        return response.json(), password

    def create_ui(self, page, key):
        page.get_by_role('button', name='工作空间', exact=True).click()
        page.get_by_role('button', name='创建工作空间', exact=True).click()
        page.locator('#workspace-key').fill(key)
        page.locator('#workspace-name').fill('空间 ' + key)
        page.get_by_role('button', name='保存空间设置', exact=True).click()
        expect(page.get_by_role('dialog')).not_to_be_visible()
        page.get_by_role('region', name='空间详情').wait_for()
        return next(space for space in page.request.get(BASE + '/api/me').json()['workspaces'] if space['workspaceKey'] == key)

    def register_ui(self, page, package_id):
        page.get_by_role('button', name='登记模型包', exact=True).click()
        page.locator('#workspace-package').fill(package_id)
        page.get_by_role('button', name='保存空间设置', exact=True).click()
        expect(page.get_by_role('dialog')).not_to_be_visible()
        expect(page.get_by_role('region', name='空间详情')).to_contain_text(package_id)

    def test_create_space_register_package_select_and_save_real_draft(self):
        self.login()
        space = self.create_ui(self.page, 'browser-' + uuid.uuid4().hex)
        package_id = 'workspace.new' + uuid.uuid4().hex
        self.register_ui(self.page, package_id)
        self.page.locator('#current-workspace').select_option(space['workspaceId'])
        self.page.get_by_role('button', name='Native 本体建模', exact=True).click()
        self.page.get_by_role('heading', name='模型包', exact=True).wait_for()
        self.page.get_by_role('button', name='创建模型包', exact=True).click()
        self.page.locator('#create-package-id').fill(package_id)
        self.page.get_by_role('button', name='创建并继续', exact=False).click()
        self.page.locator('#obj-id').fill('workspace-object')
        self.page.locator('#obj-api').fill('WorkspaceObject')
        self.page.get_by_role('button', name='保存编辑进度', exact=True).click()
        self.page.get_by_text('当前编辑已与服务器同步', exact=False).wait_for()
        self.page.reload()
        expect(self.page.locator('#obj-id')).to_have_value('workspace-object')
        expect(self.page.locator('#current-workspace')).to_have_value(space['workspaceId'])
        self.page.get_by_role('button', name='工作空间', exact=True).click()
        self.page.get_by_role('button', name='查看空间 ' + space['workspaceKey'], exact=True).click()
        self.page.set_viewport_size({'width': 390, 'height': 844})
        self.assertLessEqual(self.page.evaluate('document.documentElement.scrollWidth'), 390)
        self.screenshot('workspace-mobile.png')

    def test_normal_owner_grants_editor_revokes_and_keeps_last_owner(self):
        self.login()
        owner, password = self.account()
        peer, peer_password = self.account()
        one, two = self.browser.new_context(), self.browser.new_context()
        try:
            page, peer_page = one.new_page(), two.new_page()
            AccountManagementBrowserTest.login_page(self, page, owner['username'], password)
            AccountManagementBrowserTest.login_page(self, peer_page, peer['username'], peer_password)
            space = self.create_ui(page, 'owned-' + uuid.uuid4().hex)
            package_id = 'workspace.owned' + uuid.uuid4().hex
            self.register_ui(page, package_id)
            draft = self.mutate(page.request, 'post', '/api/model/drafts', {'packageId': package_id})
            self.assertEqual(draft.status, 201)
            draft_url = BASE + '/api/model/drafts/' + draft.json()['draftId']
            self.assertEqual(peer_page.request.get(draft_url).status, 403)
            page.locator('#workspace-candidate').fill(peer['username'])
            page.get_by_role('button', name='搜索主体', exact=True).click()
            page.get_by_role('button', name='添加成员 ' + peer['username'], exact=True).click()
            page.get_by_label('EDITOR · 草稿编辑及已有只读查询', exact=True).check()
            page.get_by_role('button', name='保存空间设置', exact=True).click()
            expect(page.get_by_role('dialog')).not_to_be_visible()
            self.assertEqual(peer_page.request.get(draft_url).status, 200)
            page.once('dialog', lambda dialog: dialog.accept())
            page.get_by_role('button', name='移除成员 ' + peer['username'], exact=True).click()
            expect(page.get_by_role('button', name='移除成员 ' + peer['username'], exact=True)).not_to_be_visible()
            self.assertEqual(peer_page.request.get(draft_url).status, 403)
            page.once('dialog', lambda dialog: dialog.accept())
            page.get_by_role('button', name='移除成员 ' + owner['username'], exact=True).click()
            page.get_by_role('alert').filter(has_text='最后一个可用 OWNER').wait_for()
            self.assertEqual(self.page.request.get(BASE + '/api/model/drafts/' + draft.json()['draftId']).status, 403)
        finally:
            one.close()
            two.close()

    def test_switch_cancel_keeps_unsaved_editor_confirm_clears_it(self):
        self.login()
        original = self.page.locator('#current-workspace').input_value()
        space = self.create_ui(self.page, 'switch-' + uuid.uuid4().hex)
        package_id = 'workspace.switch' + uuid.uuid4().hex
        self.register_ui(self.page, package_id)
        self.page.locator('#current-workspace').select_option(space['workspaceId'])
        self.page.get_by_role('button', name='创建模型包', exact=True).click()
        self.page.locator('#create-package-id').fill(package_id)
        self.page.get_by_role('button', name='创建并继续', exact=False).click()
        self.page.locator('#obj-id').fill('keep-workspace-edit')
        self.page.once('dialog', lambda dialog: dialog.dismiss())
        self.page.locator('#current-workspace').select_option(original)
        expect(self.page.locator('#obj-id')).to_have_value('keep-workspace-edit')
        expect(self.page.locator('#current-workspace')).to_have_value(space['workspaceId'])
        self.page.once('dialog', lambda dialog: dialog.accept())
        self.page.locator('#current-workspace').select_option(original)
        self.page.get_by_role('heading', name='模型包', exact=True).wait_for()
        expect(self.page.get_by_role('row').filter(has_text=package_id)).not_to_be_visible()
        self.assertEqual(self.page.locator('#obj-id').count(), 0)

    def test_membership_refresh_clears_removed_space_and_stale_preference(self):
        self.login()
        peer, password = self.account()
        key = 'revoke-' + uuid.uuid4().hex
        response = self.mutate(self.page.request, 'post', '/api/platform/workspaces', {'workspaceKey': key, 'displayName': '撤权测试空间'})
        space = response.json()
        added = self.mutate(self.page.request, 'put', '/api/platform/workspaces/' + space['workspaceId'] + '/members/' + peer['principalId'], {'expectedVersion': space['version'], 'roles': ['EDITOR']})
        self.assertEqual(added.status, 200)
        other = self.browser.new_context()
        try:
            page = other.new_page()
            AccountManagementBrowserTest.login_page(self, page, peer['username'], password)
            expect(page.locator('#current-workspace')).to_have_value(space['workspaceId'])
            page.evaluate("([principal,space])=>localStorage.setItem('ontoforge:workspace:' + principal, space)", [peer['principalId'], space['workspaceId']])
            removed = self.mutate(self.page.request, 'delete', '/api/platform/workspaces/' + space['workspaceId'] + '/members/' + peer['principalId'] + '?expectedVersion=' + str(added.json()['workspace']['version']))
            self.assertEqual(removed.status, 204)
            page.get_by_role('button', name='刷新成员权限', exact=True).click()
            expect(page.locator('#current-workspace')).to_have_value('')
            self.assertEqual(page.request.get(BASE + '/api/model/drafts?workspaceId=' + space['workspaceId']).status, 403)
            page.reload()
            expect(page.locator('#current-workspace')).to_have_value('')
        finally:
            other.close()


if __name__ == '__main__':
    unittest.main()
