// Layout structure and appearance controls adapted from Yak Ops AppLayout/TopBar.
import { useState, type ReactNode } from 'react';
import { Activity, Blocks, Database, GitBranch, Monitor, Palette, LogOut } from 'lucide-react';
import { Button } from '../shared/ui/yak/button';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '../shared/ui/yak/dialog';
import { useTheme, type ThemeMode } from '../shared/theme/ThemeProvider';
import type { WorkspaceIdentity } from '../shared/api/auth';

const navigation = [
  { label: '系统概览', path: '#/system', prefix: '#/system', icon: Activity, section: '工作台' },
  { label: 'Native 本体建模', path: '#/model/packages', prefix: '#/model', icon: Blocks, section: '本体设计' },
  { label: '通用数据绑定 · M5', path: '#/mvp/bindings', prefix: '#/mvp/bindings', icon: Database, section: '数据集成' },
  { label: '对象读取 · M6', path: '#/mvp/objects', prefix: '#/mvp/objects', icon: Database, section: '数据集成' },
  { label: '对象集合查询 · M7', path: '#/mvp/object-sets', prefix: '#/mvp/object-sets', icon: GitBranch, section: '查询分析' },
  { label: '工业质量追溯 · MVP', path: '#/mvp/quality', prefix: '#/mvp/quality', icon: GitBranch, section: '查询分析' },
];

type AppShellProps = {
  children: ReactNode;
  location: string;
  navigate: (path: string) => void;
  identity?: WorkspaceIdentity;
  dirty: boolean;
  logout: () => void;
};

export default function AppShell({ children, location, navigate, identity, dirty, logout }: AppShellProps) {
  const [appearanceOpen, setAppearanceOpen] = useState(false);
  const { theme, resolvedTheme, persistent, setTheme } = useTheme();
  const active = navigation.find(item => location.startsWith(item.prefix)) ?? navigation[0];
  const local = identity?.issuer === 'local-mvp';
  return <div className="app-shell">
    <a className="skip-link" href="#workspace-content" onClick={event => {
      event.preventDefault(); document.getElementById('workspace-content')?.focus();
    }}>跳至工作区</a>
    <header className="app-topbar">
      <div className="app-brand"><span className="app-brand-mark"><Blocks size={19}/></span>
        <strong>OntoForge <span>砺元</span></strong></div>
      <span className="app-product">本体工作台</span>
      <div className="app-topbar-actions">
        <span className="app-identity">{identity ? (local ? '本机隔离 MVP · 无登录' : identity.displayName) : '身份待验证'}</span>
        <Button variant="ghost" className="app-topbar-button" aria-label="界面设置" onClick={() => setAppearanceOpen(true)}><Palette size={16}/><span>外观</span></Button>
        {identity && !local && <Button variant="ghost" className="app-topbar-button" onClick={logout}><LogOut size={15}/>退出会话</Button>}
      </div>
    </header>
    <div className="app-body">
      <aside className="app-sidebar" aria-label="主导航">
        <div className="app-sidebar-caption">ONTOFORGE / WORKSPACE</div>
        <nav aria-label="功能导航">
          {navigation.map((item, index) => <div className="app-nav-item" key={item.path}>
            {(index === 0 || item.section !== navigation[index - 1].section) && <p className="app-nav-section">{item.section}</p>}
            <button type="button" className={'app-nav-link' + (active.path === item.path ? ' active' : '')}
              aria-current={active.path === item.path ? 'page' : undefined} onClick={() => navigate(item.path)}>
              <item.icon size={17}/><span>{item.label}</span>
            </button>
          </div>)}
        </nav>
        <div className="app-sidebar-footer"><span className="app-mode-dot"/>{local ? '单人本机开发' : '本体与质量追溯 MVP'}
          <small>Native Model · Binding · ObjectSet</small></div>
      </aside>
      <div className="app-workspace">
        <div className="app-breadcrumb"><span>{active.section}</span><span aria-hidden="true">/</span><strong>{active.label}</strong>
          {dirty && <span className="app-unsaved" role="status">有未保存的编辑</span>}
        </div>
        <main id="workspace-content" className="main" tabIndex={-1}>{children}</main>
      </div>
    </div>
    <Dialog open={appearanceOpen} onOpenChange={setAppearanceOpen}>
      <DialogContent className="appearance-dialog">
        <div className="appearance-heading"><span><Palette size={20}/></span><DialogTitle>界面设置</DialogTitle></div>
        <DialogDescription>主题偏好保存在当前浏览器中。</DialogDescription>
        <label htmlFor="appearance-theme">界面主题</label>
        <select id="appearance-theme" value={theme} onChange={event => setTheme(event.target.value as ThemeMode)}>
          <option value="light">浅色</option><option value="dark">深色</option><option value="system">跟随系统</option>
        </select>
        <p className="appearance-state" role="status"><Monitor size={14}/>当前主题：{resolvedTheme === 'dark' ? '深色' : '浅色'}</p>
        {!persistent && <p role="alert">浏览器不允许保存偏好，主题将在本次页面使用期间生效。</p>}
        <div className="appearance-actions"><Button variant="primary" onClick={() => setAppearanceOpen(false)}>完成</Button></div>
      </DialogContent>
    </Dialog>
  </div>;
}
