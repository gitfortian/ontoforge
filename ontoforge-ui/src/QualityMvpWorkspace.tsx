import React, {useEffect,useState} from 'react';
import {ArrowRight, Blocks, Database, GitBranch, RefreshCw, Search} from 'lucide-react';
import './model.css';

type Status={packageId:string;draftId:string|null;revision:number|null;
  validation:string;bound:boolean;sampleBatchId:string};
type Binding={packageId:string;draftId:string;boundRevision:number;sourceMap:{
  objectTypes:Array<{objectTypeId:string;table:string;key:string}>;
  links:Array<{linkTypeId:string;sourceColumn:string;targetColumn:string}>};sourceBatchCount:number};
type Trace={batchId:string;qualityStatus:string;
  objects:Array<{objectTypeId:string;id:string;label:string;sourceTable:string}>;
  links:Array<{linkTypeId:string;from:string;to:string;sourceColumn:string}>;
  provenance:string};
type ApiError={code?:string;message?:string};
const api='/api/mvp/quality';
async function invoke<T>(route:string,method='GET'):Promise<T>{
  const response=await fetch(api+route,{method,cache:'no-store'});
  const result=await response.json() as T&ApiError;
  if(!response.ok)throw new Error((result.code??'ERROR')+' · '+(result.message??'请求失败'));
  return result;
}

/** S0 local experience: not an industrial connector, official ObjectSet or Agent. */
export default function QualityMvpWorkspace({navigate}:{navigate:(route:string)=>void}){
  const [status,setStatus]=useState<Status|null>(null);
  const [binding,setBinding]=useState<Binding|null>(null);
  const [trace,setTrace]=useState<Trace|null>(null);
  const [batch,setBatch]=useState('B2026-001');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [loading,setLoading]=useState(true);
  async function reload(){
    const state=await invoke<Status>('/status');
    setStatus(state);return state;
  }
  useEffect(()=>{let live=true;
    void invoke<Status>('/status').then(x=>{if(live)setStatus(x)})
      .catch(e=>{if(live)setError(String(e))}).finally(()=>{if(live)setLoading(false)});
    return()=>{live=false};
  },[]);
  async function run<T>(request:()=>Promise<T>,finished:(value:T)=>void){
    setBusy(true);setError('');
    try{const value=await request();finished(value)}
    catch(e){setError(e instanceof Error?e.message:String(e))}
    finally{setBusy(false)}
  }
  function model(){
    void run(()=>invoke<Status>('/model','POST'),value=>{
      setStatus(value);setTrace(null);setBinding(null);
    });
  }
  function bind(){
    void run(()=>invoke<Binding>('/bind','POST'),value=>{
      setBinding(value);setTrace(null);
      void reload().catch(e=>setError(String(e)));
    });
  }
  function search(){
    void run(()=>invoke<Trace>('/trace?batchId='+encodeURIComponent(batch)),setTrace);
  }
  const modelReady=status?.validation==='VALID';
  return <div className="model-page">
    <div className="model-head">
      <div><div className="model-eyebrow">ONTOFORGE / ONE GOLDEN STORY</div>
        <h1>工业质量追溯 · 精简 MVP</h1>
        <p>真实 PostgreSQL 示例数据：定义本体 → 建立来源绑定 → 沿业务关系追溯订单。只验证这一条路径。</p>
      </div>
      <button className="model-btn" onClick={()=>void run(reload,()=>{})} disabled={busy}><RefreshCw size={15}/> 更新状态</button>
    </div>
    <div className="model-alert warning">隔离演示：本机开发用合成数据，不连接真实 MES/ERP，不提供企业权限、正式发布、Agent 或 Action。</div>
    {error&&<div role="alert" className="model-alert error">{error}</div>}
    {loading&&<p>正在读取本地 PostgreSQL 状态…</p>}
    <section className="model-panel">
      <header className="model-panel-head"><div><h2>01 · 定义业务本体</h2>
        <p>复用现有 Native Draft API，创建三个 ObjectType、属性和两条 LinkType，并通过真实模型校验。</p>
      </div><Blocks size={20}/></header>
      <p>生产批次 <ArrowRight size={13} style={{display:'inline'}}/> 生产工单 <ArrowRight size={13} style={{display:'inline'}}/> 客户订单</p>
      <div className="model-actions">
        <button className="model-btn model-primary" disabled={busy||Boolean(status?.draftId)}
          onClick={model}>创建示例本体</button>
        {status?.draftId&&<button className="model-btn" onClick={()=>navigate('#/model/drafts/'+status.draftId)}>
          打开真实建模工作台 <ArrowRight size={15}/></button>}
      </div>
      <p className="model-help">模型包 {status?.packageId??'manufacturing.quality'} · {modelReady?'VALID 校验通过':status?.validation??'未创建'} · revision {status?.revision??'—'}。不修改或覆盖已有用户编辑。</p>
    </section>
    <section className="model-panel">
      <header className="model-panel-head"><div><h2>02 · 绑定 PostgreSQL 来源</h2>
        <p>为三个对象类型建立持久来源映射；绑定与当次通过校验的模型 revision 关联。</p>
      </div><Database size={20}/></header>
      <div className="model-metrics">
        <div><span>ProductionBatch</span><b>mvp_source_batches</b></div>
        <div><span>WorkOrder</span><b>mvp_source_work_orders</b></div>
        <div><span>CustomerOrder</span><b>mvp_source_orders</b></div>
      </div>
      <div className="model-actions"><button className="model-btn model-primary"
        disabled={busy||!modelReady} onClick={bind}>绑定示例数据源</button>
        <span>{status?.bound?'已绑定当前模型修订':'尚未绑定当前模型'}</span></div>
      {binding&&<p role="status">绑定已保存 PostgreSQL：revision {binding.boundRevision}，样例批次 {binding.sourceBatchCount} 条。</p>}
    </section>
    <section className="model-panel">
      <header className="model-panel-head"><div><h2>03 · 沿关系查找受影响订单</h2>
        <p>输入生产批次编号，读取真实来源行和关系证据，而非由前端拼凑预设答案。</p>
      </div><GitBranch size={20}/></header>
      <label htmlFor="mvp-batch-id">生产批次 ID</label>
      <div className="model-actions">
        <input id="mvp-batch-id" value={batch} onChange={e=>setBatch(e.target.value)}
          placeholder="B2026-001" style={{maxWidth:260,padding:9}}/>
        <button className="model-btn model-primary" disabled={busy||!status?.bound||!batch.trim()}
          onClick={search}><Search size={15}/> 查询影响订单</button>
      </div>
      {!status?.bound&&<p className="model-help">完成第 1、2 步后才能查询；模型修改后需重新校验并绑定。</p>}
      {trace&&<div aria-label="真实关联查询结果" className="model-panel">
        <h3>批次 {trace.batchId} 的业务追溯结果</h3>
        <p>质量状态：<strong>{trace.qualityStatus}</strong> · 来源：隔离 PostgreSQL 合成数据</p>
        <div className="model-metrics">{trace.objects.map(item=><div key={item.objectTypeId}>
          <span>{item.objectTypeId}</span><b>{item.id}</b>
          <small>{item.label} · {item.sourceTable}</small>
        </div>)}</div>
        <h3>本体关系与来源证据</h3>
        <ul>{trace.links.map(item=><li key={item.linkTypeId}>
          {item.from} → {item.to} · {item.linkTypeId} · 来源字段 {item.sourceColumn}
        </li>)}</ul>
        <p className="model-help">这是两跳、固定安全白名单的只读关系查询示例，不代表通用 ObjectSet 已完成。</p>
      </div>}
    </section>
  </div>;
}
