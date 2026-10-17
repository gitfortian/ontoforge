import React, {useEffect,useState} from 'react';
import {ArrowRight, Blocks, Database, GitBranch, RefreshCw, Search} from 'lucide-react';
import './model.css';

type Status={packageId:string;draftId:string|null;revision:number|null;
  validation:string;bound:boolean;sampleBatchId:string};
type Mapping={
  batchTable:string;batchId:string;batchStatus:string;batchWorkOrder:string;
  workTable:string;workId:string;workOrder:string;
  orderTable:string;orderId:string;orderName:string
};
type TableInfo={table:string;columns:string[]};
type Binding={packageId:string;draftId:string;boundRevision:number;sourceMap:Mapping;sourceBatchCount:number};
type SourceSample={id:string|null;attribute:string|null;relatedId:string|null};
type SourcePreview={batches:SourceSample[];workOrders:SourceSample[];
  orders:SourceSample[];linkedExample:{batchId:string;workOrderId:string;orderId:string}|null};
const defaultMap:Mapping={
  batchTable:'mvp_source_batches',batchId:'batch_id',batchStatus:'quality_status',batchWorkOrder:'work_order_id',
  workTable:'mvp_source_work_orders',workId:'work_order_id',workOrder:'order_id',
  orderTable:'mvp_source_orders',orderId:'order_id',orderName:'customer_name'
};
const groups=[
 {title:'生产批次',table:'batchTable',fields:[['batchId','批次唯一 ID'],['batchStatus','质量状态'],['batchWorkOrder','关联工单 ID']]},
 {title:'生产工单',table:'workTable',fields:[['workId','工单唯一 ID'],['workOrder','关联客户订单 ID']]},
 {title:'客户订单',table:'orderTable',fields:[['orderId','客户订单唯一 ID'],['orderName','客户名称/显示字段']]}
] as const;
type Trace={batchId:string;qualityStatus:string;
  objects:Array<{objectTypeId:string;id:string;label:string;sourceTable:string}>;
  links:Array<{linkTypeId:string;from:string;to:string;sourceColumn:string}>;
  provenance:string};
type ApiError={code?:string;message?:string};
const api='/api/mvp/quality';
async function invoke<T>(route:string,method='GET',body?:unknown):Promise<T>{
  const response=await fetch(api+route,{method,cache:'no-store',
    headers:body===undefined?undefined:{'Content-Type':'application/json'},
    body:body===undefined?undefined:JSON.stringify(body)});
  const result=await response.json() as T&ApiError;
  if(!response.ok)throw new Error((result.code??'ERROR')+' · '+(result.message??'请求失败'));
  return result;
}

/** S0 local experience: not an industrial connector, official ObjectSet or Agent. */
export default function QualityMvpWorkspace({navigate}:{navigate:(route:string)=>void}){
  const [status,setStatus]=useState<Status|null>(null);
  const [binding,setBinding]=useState<Binding|null>(null);
  const [sources,setSources]=useState<TableInfo[]>([]);
  const [map,setMap]=useState<Mapping>(defaultMap);
  const [savedMap,setSavedMap]=useState<Mapping|null>(null);
  const [preview,setPreview]=useState<SourcePreview|null>(null);
  const [previewMap,setPreviewMap]=useState('');
  const [expandMapping,setExpandMapping]=useState(false);
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
    void Promise.all([
      invoke<Status>('/status'),
      invoke<TableInfo[]>('/sources'),
      invoke<Mapping>('/binding').catch(()=>null)
    ]).then(([current,catalog,saved])=>{
      if(!live)return;
      setStatus(current);setSources(catalog);
      if(saved){setMap(saved);setSavedMap(saved)}
    }).catch(e=>{if(live)setError(String(e))}
    ).finally(()=>{if(live)setLoading(false)});
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
    void run(()=>invoke<Binding>('/bind','POST',map),value=>{
      setBinding(value);setSavedMap(value.sourceMap);setTrace(null);
      void reload().catch(e=>setError(String(e)));
    });
  }
  function inspect(){
    const selected={...map};
    void run(()=>invoke<SourcePreview>('/preview','POST',selected),value=>{
      setPreview(value);setPreviewMap(JSON.stringify(selected));
    });
  }
  function search(){
    void run(()=>invoke<Trace>('/trace?batchId='+encodeURIComponent(batch)),setTrace);
  }
  const modelReady=status?.validation==='VALID';
  const mapChanged=Boolean(savedMap&&JSON.stringify(map)!==JSON.stringify(savedMap));
  const validMapping=groups.every(group=>{
    const table=map[group.table];
    const columns=sources.find(x=>x.table===table)?.columns??[];
    return Boolean(table)&&group.fields.every(([field])=>columns.includes(map[field]));
  });
  function setSourceField(field:keyof Mapping,value:string){
    setMap(current=>{
      const copy={...current,[field]:value};
      for(const g of groups)if(g.table===field)
        for(const [key] of g.fields)copy[key]='';
      return copy;
    });
    setTrace(null);setPreview(null);setPreviewMap('');
  }
  const currentPreview=previewMap===JSON.stringify(map)?preview:null;
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
        <p>选择当前 PostgreSQL 的测试表与关联字段；映射会保存，并绑定到当前模型 revision。</p>
      </div><Database size={20}/></header>
      <div className="model-metrics">{groups.map(group=><div key={group.table}>
        <span>{group.title}</span><b>{map[group.table]||'未选表'}</b>
      </div>)}</div>
      <div className="model-actions">
        <button className="model-btn" aria-expanded={expandMapping} aria-controls="mvp-source-editor"
          onClick={()=>setExpandMapping(!expandMapping)}>{expandMapping?'收起字段映射':'选择 PostgreSQL 表与字段'}</button>
        <span>{status?.bound&&!mapChanged?'当前修订已绑定':'尚未保存当前来源映射'}</span>
      </div>
      {expandMapping&&<div id="mvp-source-editor" className="model-panel" aria-label="PostgreSQL 来源映射">
        <p className="model-help">仅显示当前本机 public 下 demo_ / mvp_source_ 测试表。不支持任意 SQL、生产源或远程数据源。</p>
        {groups.map(group=><div key={group.table} className="model-form-grid">
          <h3 className="model-form-wide">{group.title}</h3>
          <div><label htmlFor={'source-'+group.table}>来源表</label>
            <select id={'source-'+group.table} value={map[group.table]}
              onChange={e=>setSourceField(group.table,e.target.value)}>
              <option value="">请选择来源表</option>
              {sources.map(table=><option key={table.table} value={table.table}>{table.table}</option>)}
            </select></div>
          {group.fields.map(([key,title])=><div key={key}>
            <label htmlFor={'source-'+key}>{title}</label>
            <select id={'source-'+key} value={map[key]}
              onChange={e=>setSourceField(key,e.target.value)}>
              <option value="">请选择字段</option>
              {(sources.find(t=>t.table===map[group.table])?.columns??[])
                .map(col=><option key={col} value={col}>{col}</option>)}
            </select>
          </div>)}
        </div>)}
      </div>}
      <div className="model-actions">
        <button className="model-btn" onClick={inspect} disabled={busy||!validMapping}>
          <Search size={15}/> 预览真实样本与关联
        </button>
        <span className="model-help">只读检查当前选择，不会保存或改变已有绑定</span>
      </div>
      {currentPreview&&<div className="model-panel" aria-label="来源预览结果">
        <h3>来源样本（每表最多三条）</h3>
        <div className="model-metrics">
          {[
            {title:'生产批次',rows:currentPreview.batches},
            {title:'生产工单',rows:currentPreview.workOrders},
            {title:'客户订单',rows:currentPreview.orders}
          ].map(group=><div key={group.title}>
            <span>{group.title}</span>
            {group.rows.length===0?<p>来源表暂无记录</p>:
              <ul>{group.rows.map((row,i)=><li key={i}>
                <b>{row.id??'空 ID'}</b>
                <small> · {row.attribute??'空值'} · 关联值 {row.relatedId??'空值'}</small>
              </li>)}</ul>}
          </div>)}
        </div>
        {currentPreview.linkedExample?<p role="status">
          找到一条可连通的真实来源路径：
          <strong> {currentPreview.linkedExample.batchId} → {currentPreview.linkedExample.workOrderId} → {currentPreview.linkedExample.orderId}</strong>
        </p>:<p role="status">当前三张来源表中没有找到可连通的两跳记录，请核对关联字段或测试数据。</p>}
        <p className="model-help">这里只预览样本及一条关联证据，并不代表全部数据都能正确关联。确认后再保存来源绑定。</p>
      </div>}
      <div className="model-actions"><button className="model-btn model-primary"
        disabled={busy||!modelReady||!validMapping} onClick={bind}>保存来源绑定</button>
        <span>{mapChanged?'当前字段已修改，需重新保存':'映射与服务器一致或尚未建立'}</span></div>
      {binding&&<p role="status">绑定已保存 PostgreSQL：revision {binding.boundRevision}，来源批次前 {binding.sourceBatchCount} 条。</p>}
    </section>
    <section className="model-panel">
      <header className="model-panel-head"><div><h2>03 · 沿关系查找受影响订单</h2>
        <p>输入你选择的来源表中的批次 ID，查询受控的两跳关系并显示来源字段。</p>
      </div><GitBranch size={20}/></header>
      <label htmlFor="mvp-batch-id">生产批次 ID</label>
      <div className="model-actions">
        <input id="mvp-batch-id" value={batch} onChange={e=>setBatch(e.target.value)}
          placeholder="B2026-001" style={{maxWidth:260,padding:9}}/>
        <button className="model-btn model-primary" disabled={busy||!status?.bound||mapChanged||!batch.trim()}
          onClick={search}><Search size={15}/> 查询影响订单</button>
      </div>
      {!status?.bound&&<p className="model-help">完成第 1、2 步后才能查询；模型修改后需重新校验并绑定。</p>}
      {trace&&<div aria-label="真实关联查询结果" className="model-panel">
        <h3>批次 {trace.batchId} 的业务追溯结果</h3>
        <p>质量状态：<strong>{trace.qualityStatus}</strong> · 来源：{trace.provenance==='LOCAL_TEST_POSTGRES'?'当前本机 PostgreSQL 测试表':'内置合成 PostgreSQL 数据'}</p>
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
