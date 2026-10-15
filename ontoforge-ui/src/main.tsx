import React, { useCallback, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Activity, Blocks, Database, GitBranch, ShieldCheck, RefreshCw } from 'lucide-react';
import NativeModelWorkspace from './NativeModelWorkspace';
import './style.css';

type Status = {
  service: string;
  version: string;
  database: string;
  installationId: string;
  modelModule: string;
  authModule: string;
};
type LoadState = {kind:'loading'}|{kind:'error'}|{kind:'ready';data:Status};
const planned=[
  {title:'数据绑定与关联',detail:'Data Binding / ObjectSet',icon:GitBranch,stage:'P1'},
  {title:'用户与访问控制',detail:'User / Policy',icon:ShieldCheck,stage:'后续'}
];
function App() {
  const [status,setStatus]=useState<LoadState>({kind:'loading'});
  const [location,setLocation]=useState(window.location.hash);
  const [dirty,setDirty]=useState(false);
  const onDirtyChange=useCallback((v:boolean)=>setDirty(v),[]);
  const model=location.startsWith('#/model');
  async function reload() {
    setStatus({kind:'loading'});
    try {
      const response=await fetch('/api/system/status',{cache:'no-store'});
      if(!response.ok)throw new Error('unavailable');
      const data:Status=await response.json();
      if(data.database!=='CONNECTED')throw new Error('unavailable');
      setStatus({kind:'ready',data});
    }catch{setStatus({kind:'error'});}
  }
  useEffect(()=>{void reload()},[]);
  useEffect(()=>{
    const onHash=()=>setLocation(window.location.hash);
    window.addEventListener('hashchange',onHash);
    return ()=>window.removeEventListener('hashchange',onHash);
  },[]);
  function navigate(hash:string) {
    if(dirty&&!window.confirm('存在尚未保存的本地编辑。离开会放弃这些更改，是否继续？'))return;
    window.location.hash=hash;
    if(hash===location)setLocation(hash);
  }
  return <div className="layout">
    <aside className="sidebar">
      <div className="brand"><div className="brand-icon"><Blocks size={20}/></div><div><strong>OntoForge · 砺元</strong><small>工业运营本体平台</small></div></div>
      <p className="nav-heading">平台与建模</p>
      <button className={model?'nav-disabled nav-link':'nav-active nav-link'} onClick={()=>navigate('#/system')}><Activity size={17}/> 系统概览</button>
      <button className={model?'nav-active nav-link':'nav-disabled nav-link'} onClick={()=>navigate('#/model/packages')}><Blocks size={17}/> Native 本体建模</button>
      <p className="nav-heading secondary-label">规划模块</p>
      {planned.map(item=><div key={item.title} className="nav-disabled" title="当前版本暂不开放"><item.icon size={16}/>{item.title}<span>规划中</span></div>)}
      <div className="sidebar-bottom">S0 · ObjectType 草稿建模<br/><small>本地开发验证 · 无认证/发布</small></div>
    </aside>
    <main className="main">
      <div className="topline"><span>{model?'本体设计 / ObjectType':'平台基座 / 系统概览'}</span><span className="env-chip">LOCAL DEVELOPMENT</span></div>
      {model?<NativeModelWorkspace onDirtyChange={onDirtyChange}/>:<>
        <div className="intro"><p className="eyebrow">S0 · FOUNDATION</p><h1>系统概览</h1><p>系统概览来自真实 Spring Boot + PostgreSQL。Native 本体建模当前只提供草稿编辑与校验。</p></div>
        <div className="panel">
          <div className="panel-top"><div><h2>运行状态</h2><p>来自后端实时 API，并非演示数据</p></div><button type="button" onClick={()=>void reload()}><RefreshCw size={15}/> 重新检测</button></div>
          <div className="health-row"><span className="service-label"><Activity size={18}/> API 服务</span>{status.kind==='ready'?<span className="ok"><i/> 已连接 · {status.data.version}</span>:status.kind==='loading'?<span className="pending">检测中</span>:<span className="error">连接失败</span>}</div>
          <div className="health-row"><span className="service-label"><Database size={18}/> PostgreSQL</span>{status.kind==='ready'?<span className="ok"><i/> 已连接 · 已执行迁移</span>:status.kind==='loading'?<span className="pending">检测中</span>:<span className="error">无法确认</span>}</div>
          <div className="health-row"><span className="service-label"><Blocks size={18}/> 本体草稿建模</span>{status.kind==='ready'&&status.data.modelModule==='DRAFT_AVAILABLE'?<span className="ok"><i/> ObjectType 草稿已启用</span>:<span className="pending">暂不可用</span>}</div>
          {status.kind==='ready'&&<div className="identity">Installation ID <code>{status.data.installationId}</code></div>}
          {status.kind==='error'&&<p className="trouble">无法读取真实运行状态。请检查 Spring Boot 和 PostgreSQL 是否已启动。</p>}
        </div>
        <div className="section-heading"><h2>下一阶段建设</h2><span>以下内容当前均未实现</span></div>
        <div className="cards">{planned.map(item=><div key={item.title} className="feature"><div className="feature-icon"><item.icon size={20}/></div><div className="stage">{item.stage}</div><h3>{item.title}</h3><p>{item.detail}</p><span className="planned">暂未开放</span></div>)}</div>
        <footer>OntoForge · S0-MVP-0.2：尚未实现认证、授权、模型发布、数据绑定或工业 Action。</footer>
      </>}
    </main>
  </div>;
}
createRoot(document.getElementById('root')!).render(<React.StrictMode><App/></React.StrictMode>);
