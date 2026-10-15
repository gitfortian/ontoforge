import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Activity, Blocks, Database, GitBranch, ShieldCheck, RefreshCw } from 'lucide-react';
import './style.css';

type Status = {
  service: string;
  version: string;
  database: string;
  installationId: string;
  modelModule: string;
  authModule: string;
};

type LoadState = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; data: Status };

const planned = [
  { title: 'Native 本体建模', detail: 'ObjectType / LinkType', icon: Blocks, stage: 'P0-A' },
  { title: '数据绑定与关联', detail: 'Data Binding / ObjectSet', icon: GitBranch, stage: 'P1' },
  { title: '用户与访问控制', detail: 'User / Policy', icon: ShieldCheck, stage: '后续' },
];

function App() {
  const [status, setStatus] = useState<LoadState>({ kind: 'loading' });
  async function reload() {
    setStatus({ kind: 'loading' });
    try {
      const response = await fetch('/api/system/status', { cache: 'no-store' });
      if (!response.ok) throw new Error('unavailable');
      const data: Status = await response.json();
      if (data.database !== 'CONNECTED') throw new Error('unavailable');
      setStatus({ kind: 'ready', data });
    } catch {
      setStatus({ kind: 'error' });
    }
  }
  useEffect(() => { void reload(); }, []);

  return <div className="layout">
    <aside className="sidebar">
      <div className="brand"><div className="brand-icon"><Blocks size={20}/></div><div><strong>Ontology System</strong><small>企业运营本体平台</small></div></div>
      <p className="nav-heading">工作空间</p>
      <div className="nav-active"><Activity size={17}/> 系统概览</div>
      <p className="nav-heading secondary-label">规划模块</p>
      {planned.map(item => <div key={item.title} className="nav-disabled" title="当前版本暂不开放"><item.icon size={16}/>{item.title}<span>规划中</span></div>)}
      <div className="sidebar-bottom">S0 · 最小可运行版本<br/><small>仅供本地开发验证</small></div>
    </aside>
    <main className="main">
      <div className="topline"><span>平台基座</span><span className="env-chip">LOCAL DEVELOPMENT</span></div>
      <div className="intro"><p className="eyebrow">S0 · FOUNDATION</p><h1>系统概览</h1><p>第一步，先确保前端、后端与 PostgreSQL 真实连通。其余能力遵循架构规划逐版本交付。</p></div>
      <div className="panel">
        <div className="panel-top"><div><h2>运行状态</h2><p>来自后端实时 API，并非演示数据</p></div><button type="button" onClick={() => void reload()}><RefreshCw size={15}/> 重新检测</button></div>
        <div className="health-row"><span className="service-label"><Activity size={18}/> API 服务</span>{status.kind==='ready' ? <span className="ok"><i/> 已连接 · {status.data.version}</span> : status.kind==='loading'? <span className="pending">检测中</span> : <span className="error">连接失败</span>}</div>
        <div className="health-row"><span className="service-label"><Database size={18}/> PostgreSQL</span>{status.kind==='ready' ? <span className="ok"><i/> 已连接 · 已执行迁移</span> : status.kind==='loading'? <span className="pending">检测中</span> : <span className="error">无法确认</span>}</div>
        {status.kind==='ready' && <div className="identity">Installation ID <code>{status.data.installationId}</code></div>}
        {status.kind==='error' && <p className="trouble">无法读取真实运行状态。请检查 Spring Boot 和 PostgreSQL 是否已启动。</p>}
      </div>
      <div className="section-heading"><h2>下一阶段建设</h2><span>以下内容当前均未实现</span></div>
      <div className="cards">{planned.map(item=><div key={item.title} className="feature"><div className="feature-icon"><item.icon size={20}/></div><div className="stage">{item.stage}</div><h3>{item.title}</h3><p>{item.detail}</p><span className="planned">暂未开放</span></div>)}</div>
      <footer>架构依据：V1.3 · S0-MVP-0.1 不包含用户认证、权限与业务数据。</footer>
    </main>
  </div>;
}

createRoot(document.getElementById('root')!).render(<React.StrictMode><App/></React.StrictMode>);
