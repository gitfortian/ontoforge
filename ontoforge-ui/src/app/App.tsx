import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Activity, Blocks, Database, GitBranch, ShieldCheck, RefreshCw } from 'lucide-react';
import NativeModelWorkspace from '../features/design/modeling/NativeModelWorkspace';
import RuntimeTypeWorkspace from '../features/design/modeling/RuntimeTypeWorkspace';
import QualityMvpWorkspace from '../features/analysis/impact/QualityMvpWorkspace';
import DataBindingWorkspace from '../features/integration/bindings/DataBindingWorkspace';
import ObjectRuntimeWorkspace from '../features/objects/explorer/ObjectRuntimeWorkspace';
import {beginSignIn,initializeIdentity,oidcConfigured,signOut,WorkspaceIdentity} from '../shared/api/auth';
import '../style.css';

type Status = {
  service: string;
  version: string;
  database: string;
  installationId: string;
  modelModule: string;
  snapshotModule: string;
  authModule: string;
};
type LoadState = {kind:'loading'}|{kind:'error'}|{kind:'ready';data:Status};
const planned=[
  {title:'通用数据连接与 ObjectSet',detail:'更多真实工业数据源与查询',icon:GitBranch,stage:'后续'},
  {title:'数据权限与策略',detail:'Object/Field Policy',icon:ShieldCheck,stage:'P1'}
];
export default function App() {
  const [status,setStatus]=useState<LoadState>({kind:'loading'});
  const [auth,setAuth]=useState<{kind:'checking'|'missing'|'error'|'ready';identity?:WorkspaceIdentity;message?:string}>({kind:'checking'});
  useEffect(()=>{
    let active=true;
    void initializeIdentity().then(identity=>{
      if(active)setAuth(identity?{kind:'ready',identity}:{kind:'missing'});
    }).catch(e=>{if(active)setAuth({kind:'error',message:e instanceof Error?e.message:'身份初始化失败'});});
    const expired=()=>setAuth({kind:'missing'});
    window.addEventListener('ontoforge-auth-expired',expired);
    return ()=>{active=false;window.removeEventListener('ontoforge-auth-expired',expired);};
  },[]);
  const logout=()=>{
    if(dirtyRef.current&&!window.confirm('尚有未保存编辑。退出会放弃当前编辑，是否继续？'))return;
    signOut();setAuth({kind:'missing'});
    navigate('#/system');
  };
  const [location,setLocation]=useState(window.location.hash);
  const [dirty,setDirty]=useState(false);
  // Synchronous refs protect navigation even before React's next paint.
  const dirtyRef=useRef(false);
  const locationRef=useRef(window.location.hash);
  const onDirtyChange=useCallback((v:boolean)=>{dirtyRef.current=v;setDirty(v)},[]);
  const model=location.startsWith('#/model');
  const binding=location.startsWith('#/mvp/bindings');
  const quality=location.startsWith('#/mvp/quality');
  const objectRuntime=location.startsWith('#/mvp/objects');
  const mvp=binding||quality||objectRuntime;
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
    const onHash=()=>{
      const next=window.location.hash;
      if(next===locationRef.current)return;
      if(dirtyRef.current && !window.confirm('存在尚未保存的本地编辑。离开会放弃这些更改，是否继续？')){
        // hashchange fires AFTER browser Back/Forward. Restore the previous
        // location without dispatching another hashchange or discarding edits.
        history.replaceState(null,'',window.location.pathname+window.location.search+(locationRef.current||'#/system'));
        return;
      }
      dirtyRef.current=false;setDirty(false);
      locationRef.current=next;
      setLocation(next);
    };
    window.addEventListener('hashchange',onHash);
    return ()=>window.removeEventListener('hashchange',onHash);
  },[]);
  function navigate(hash:string) {
    // All menu, editor and browser Back/Forward navigation uses the SAME guard.
    if(hash!==window.location.hash)window.location.hash=hash;
  }
  return <div className="layout">
    <aside className="sidebar">
      <div className="brand"><div className="brand-icon"><Blocks size={20}/></div><div><strong>OntoForge · 砺元</strong><small>工业运营本体平台</small></div></div>
      <p className="nav-heading">平台与建模</p>
      <button className={model?'nav-inactive nav-link':'nav-active nav-link'} onClick={()=>navigate('#/system')}><Activity size={17}/> 系统概览</button>
      <button className={model?'nav-active nav-link':'nav-inactive nav-link'} onClick={()=>navigate('#/model/packages')}><Blocks size={17}/> Native 本体建模</button>
      <button className={quality?'nav-active nav-link':'nav-inactive nav-link'} onClick={()=>navigate('#/mvp/quality')}><GitBranch size={17}/> 工业质量追溯 · MVP</button>
      <button className={binding?'nav-active nav-link':'nav-inactive nav-link'} onClick={()=>navigate('#/mvp/bindings')}><Database size={17}/> 通用数据绑定 · M5</button>
      <button className={objectRuntime?'nav-active nav-link':'nav-inactive nav-link'} onClick={()=>navigate('#/mvp/objects')}><Database size={17}/> 对象读取 · M6</button>
      <p className="nav-heading secondary-label">规划模块</p>
      {planned.map(item=><div key={item.title} className="nav-disabled" title="当前版本暂不开放"><item.icon size={16}/>{item.title}<span>规划中</span></div>)}
      <div className="sidebar-bottom">S0 · 本体与质量追溯 MVP<br/><small>本机 MVP 无认证；生产安全需另行集成</small></div>
    </aside>
    <main className="main">
      <div className="topline"><span>{binding?'通用数据绑定 / M5':quality?'工业质量追溯 / 精简 MVP':objectRuntime?'对象读取 / M6':model?'本体设计 / ObjectType':'平台基座 / 系统概览'}</span><span className="env-chip">{auth.kind==='ready'?(auth.identity?.issuer==='local-mvp'?'本机隔离 MVP · 无登录': '已验证身份：'+auth.identity?.displayName):'身份模式待初始化'}</span>{auth.kind==='ready'&&auth.identity?.issuer!=='local-mvp'&&<button type="button" onClick={logout}>退出会话</button>}</div>
      {(model||mvp)?(auth.kind==='ready'
        ? (binding?<DataBindingWorkspace route={location} navigate={navigate} onDirtyChange={onDirtyChange}/>:quality?<QualityMvpWorkspace navigate={navigate}/>:objectRuntime?<ObjectRuntimeWorkspace route={location} navigate={navigate}/>:location.startsWith('#/model/runtime-types/')
          ? <RuntimeTypeWorkspace route={location} navigate={navigate}/>
          : <NativeModelWorkspace identity={auth.identity!} onDirtyChange={onDirtyChange} route={location} navigate={navigate}/>)
        : <section className="panel" aria-live="polite">
            <h1>可信身份验证</h1>
            <p>{auth.kind==='checking'?'正在核对身份…':auth.kind==='error'?auth.message:'访问模型包前，需要使用组织的 OIDC 账号登录。'}</p>
            {auth.kind!=='checking'&&oidcConfigured&&<button type="button" onClick={()=>void beginSignIn().catch(e=>setAuth({kind:'error',message:String(e)}))}>使用组织账号登录</button>}
            {!oidcConfigured&&<p>尚未配置 OIDC 公共客户端。请按 B0 实施说明配置授权端点、令牌端点和客户端 ID；服务端不会开放匿名模型 API。</p>}
          </section>)
        :<>
        <div className="intro"><p className="eyebrow">S0 · FOUNDATION</p><h1>系统概览</h1><p>系统概览来自真实 Spring Boot + PostgreSQL。Native 本体建模支持本地草稿、不可变快照及结构差异，不提供正式审核/发布。</p></div>
        <div className="panel">
          <div className="panel-top"><div><h2>运行状态</h2><p>来自后端实时 API，并非演示数据</p></div><button type="button" onClick={()=>void reload()}><RefreshCw size={15}/> 重新检测</button></div>
          <div className="health-row"><span className="service-label"><Activity size={18}/> API 服务</span>{status.kind==='ready'?<span className="ok"><i/> 已连接 · {status.data.version}</span>:status.kind==='loading'?<span className="pending">检测中</span>:<span className="error">连接失败</span>}</div>
          <div className="health-row"><span className="service-label"><Database size={18}/> PostgreSQL</span>{status.kind==='ready'?<span className="ok"><i/> 已连接 · 已执行迁移</span>:status.kind==='loading'?<span className="pending">检测中</span>:<span className="error">无法确认</span>}</div>
          <div className="health-row"><span className="service-label"><Blocks size={18}/> 本体草稿建模</span>{status.kind==='ready'&&status.data.modelModule==='DRAFT_AVAILABLE'?<span className="ok"><i/> ObjectType 草稿已启用</span>:<span className="pending">暂不可用</span>}</div>
          <div className="health-row"><span className="service-label"><GitBranch size={18}/> 不可变本地快照</span>{status.kind==='ready'&&status.data.snapshotModule==='LOCAL_SNAPSHOT_AVAILABLE'?<span className="ok"><i/> 历史与结构差异已启用</span>:<span className="pending">暂不可用</span>}</div>
          {status.kind==='ready'&&<div className="identity">Installation ID <code>{status.data.installationId}</code></div>}
          {status.kind==='error'&&<p className="trouble">无法读取真实运行状态。请检查 Spring Boot 和 PostgreSQL 是否已启动。</p>}
        </div>
        <div className="section-heading"><h2>下一阶段建设</h2><span>以下内容当前均未实现</span></div>
        <div className="cards">{planned.map(item=><div key={item.title} className="feature"><div className="feature-icon"><item.icon size={20}/></div><div className="stage">{item.stage}</div><h3>{item.title}</h3><p>{item.detail}</p><span className="planned">暂未开放</span></div>)}</div>
        <footer>OntoForge · 工业追溯仅限本机隔离测试；通用认证后续复用 yak-ops HttpSession，不提供正式发布或工业 Action。</footer>
      </>}
    </main>
  </div>;
}
