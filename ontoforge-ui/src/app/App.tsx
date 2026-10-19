import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Activity, Blocks, Database, GitBranch, ShieldCheck, RefreshCw } from 'lucide-react';
import NativeModelWorkspace from '../features/design/modeling/NativeModelWorkspace';
import RuntimeTypeWorkspace from '../features/design/modeling/RuntimeTypeWorkspace';
import QualityMvpWorkspace from '../features/analysis/impact/QualityMvpWorkspace';
import DataBindingWorkspace from '../features/integration/bindings/DataBindingWorkspace';
import ObjectRuntimeWorkspace from '../features/objects/explorer/ObjectRuntimeWorkspace';
import ObjectSetWorkspace from '../features/analysis/objectsets/ObjectSetWorkspace';
import {authenticationMode,initializeIdentity,signOut,WorkspaceIdentity} from '../shared/api/auth';
import LoginPanel from '../features/platform/authentication/LoginPanel';
import { Dialog, DialogContent, DialogTitle, DialogDescription } from '../shared/ui/yak/dialog';
import AppShell from './AppShell';
import { Button } from '../shared/ui/yak/button';
import { Card } from '../shared/ui/yak/card';
import { Spinner } from '../shared/ui/yak/spinner';
import { Empty } from '../shared/ui/yak/empty';
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
    // Keep the mounted editor behind the recovery dialog so the same user can resume unsaved work.
    const expired=()=>setAuth(current=>({...current,kind:'missing'}));
    window.addEventListener('ontoforge-auth-expired',expired);
    return ()=>{active=false;window.removeEventListener('ontoforge-auth-expired',expired);};
  },[]);
  const logout=async()=>{
    if(dirtyRef.current&&!window.confirm('尚有未保存编辑。退出会放弃当前编辑，是否继续？'))return;
    try {
      await signOut();onDirtyChange(false);setAuth({kind:'missing'});navigate('#/system');
    } catch(failure) {
      setAuth(current=>({...current,kind:'error',message:failure instanceof Error?failure.message:'退出会话失败'}));
    }
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
  const objectSet=location.startsWith('#/mvp/object-sets');
  const mvp=binding||quality||objectRuntime||objectSet;
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
  function loggedIn(identity:WorkspaceIdentity) {
    if(auth.identity && auth.identity.principalId!==identity.principalId)onDirtyChange(false);
    setAuth({kind:'ready',identity});
  }
  return <AppShell location={location} navigate={navigate} identity={auth.identity} dirty={dirty} logout={()=>void logout()}
    signIn={()=>navigate('#/model/packages')}>
      {(model||mvp)?(Boolean(auth.identity)
        ? <React.Fragment key={auth.identity!.principalId}>{binding?<DataBindingWorkspace route={location} navigate={navigate} onDirtyChange={onDirtyChange}/>:quality?<QualityMvpWorkspace navigate={navigate}/>:objectSet?<ObjectSetWorkspace route={location} navigate={navigate}/>:objectRuntime?<ObjectRuntimeWorkspace route={location} navigate={navigate}/>:location.startsWith('#/model/runtime-types/')
          ? <RuntimeTypeWorkspace route={location} navigate={navigate}/>
          : <NativeModelWorkspace identity={auth.identity!} onDirtyChange={onDirtyChange} route={location} navigate={navigate}/>}</React.Fragment>
        : <Card className="panel auth-panel" aria-live="polite">
            <h1>{authenticationMode()==='oidc-jwt'?'可信身份验证':'登录 OntoForge'}</h1>
            {auth.kind==='checking'?<p>正在核对身份…</p>:<>
              {auth.message&&<p role="alert">{auth.message}</p>}
              <LoginPanel onLogin={loggedIn}/>
            </>}
          </Card>)
        :<>
        <div className="intro"><p className="eyebrow">S0 · FOUNDATION</p><h1>系统概览</h1><p>系统概览来自真实 Spring Boot + PostgreSQL。Native 本体建模支持本地草稿、不可变快照及结构差异，不提供正式审核/发布。</p></div>
        <Card className="panel">
          <div className="panel-top"><div><h2>运行状态</h2><p>来自后端实时 API，并非演示数据</p></div><Button type="button" onClick={()=>void reload()}><RefreshCw size={15}/> 重新检测</Button></div>
          <div className="health-row"><span className="service-label"><Activity size={18}/> API 服务</span>{status.kind==='ready'?<span className="ok"><i/> 已连接 · {status.data.version}</span>:status.kind==='loading'?<span className="pending"><Spinner label="检测中"/> 检测中</span>:<span className="error">连接失败</span>}</div>
          <div className="health-row"><span className="service-label"><Database size={18}/> PostgreSQL</span>{status.kind==='ready'?<span className="ok"><i/> 已连接 · 已执行迁移</span>:status.kind==='loading'?<span className="pending"><Spinner label="检测中"/> 检测中</span>:<span className="error">无法确认</span>}</div>
          <div className="health-row"><span className="service-label"><Blocks size={18}/> 本体草稿建模</span>{status.kind==='ready'&&status.data.modelModule==='DRAFT_AVAILABLE'?<span className="ok"><i/> ObjectType 草稿已启用</span>:<span className="pending">暂不可用</span>}</div>
          <div className="health-row"><span className="service-label"><GitBranch size={18}/> 不可变本地快照</span>{status.kind==='ready'&&status.data.snapshotModule==='LOCAL_SNAPSHOT_AVAILABLE'?<span className="ok"><i/> 历史与结构差异已启用</span>:<span className="pending">暂不可用</span>}</div>
          {status.kind==='ready'&&<div className="identity">Installation ID <code>{status.data.installationId}</code></div>}
          {status.kind==='error'&&<Empty title="无法读取真实运行状态" description="请检查 Spring Boot 和 PostgreSQL 是否已启动。"/>}
        </Card>
        <div className="section-heading"><h2>下一阶段建设</h2><span>以下内容当前均未实现</span></div>
        <div className="cards">{planned.map(item=><div key={item.title} className="feature"><div className="feature-icon"><item.icon size={20}/></div><div className="stage">{item.stage}</div><h3>{item.title}</h3><p>{item.detail}</p><span className="planned">暂未开放</span></div>)}</div>
        <footer>OntoForge · 本体与只读查询工作台；不提供正式发布或工业 Action。</footer>
      </>}
      <Dialog open={Boolean(auth.identity&&auth.kind!=='ready')} onOpenChange={()=>{}}>
        <DialogContent className="session-recovery">
          <DialogTitle>重新登录</DialogTitle>
          <DialogDescription>会话已失效或退出未完成。当前编辑尚未提交；使用同一账号登录可继续，切换账号将关闭原账号的编辑。</DialogDescription>
          {auth.message&&<p role="alert">{auth.message}</p>}
          <LoginPanel onLogin={loggedIn}/>
        </DialogContent>
      </Dialog>
  </AppShell>;
}
