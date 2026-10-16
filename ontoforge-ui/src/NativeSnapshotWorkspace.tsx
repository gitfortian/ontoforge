import React,{useEffect,useRef,useState} from 'react';
import {ArrowLeft,CheckCircle2,GitCompareArrows,History,LockKeyhole,RefreshCw,ShieldAlert} from 'lucide-react';
import './model.css';

type Issue={path:string;code:string;message:string};
type Report={status:string;validatedRevision:number|null;issues:Issue[]};
export type DraftBrief={draftId:string;packageId:string;revision:number;
  editingContent:{objectTypes:unknown[];linkTypes:unknown[]};validation:Report};
type Snapshot={snapshotId:string;packageId:string;sourceDraftId:string;sourceRevision:number;
  candidateSchemaVersion:string;canonicalizationProfile:string;payloadSha256:string;
  snapshotContent:unknown;createdAt:string;lifecycle:'LOCAL_SNAPSHOT_ONLY'};
type Summary={snapshotId:string;packageId:string;sourceRevision:number;
  payloadSha256:string;createdAt:string;lifecycle:'LOCAL_SNAPSHOT_ONLY'};
type Page={items:Summary[];nextCursor:string|null};
type Difference={entityKind:string;objectTypeId:string;propertyId:string|null;path:string;
  changeKind:string;before?:unknown;after?:unknown;riskLevel:string;reasonCode:string};
type Diff={fromSnapshotId:string;toSnapshotId:string;packageId:string;
  sameSnapshot:boolean;changes:Difference[];compatibilityStatus:string};
type Failure={code:string;message:string;issues?:Issue[]};
const API='/api/model';
const errorText=(e:unknown)=>{const x=e as Partial<Failure>;
  return (x.code??'API_ERROR')+' · '+(x.message??'请求失败');};
async function request<T>(path:string,method='GET',body?:unknown):Promise<T>{
  let resp:Response;
  try{resp=await fetch(API+path,{method,cache:'no-store',
    headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});}
  catch{throw {code:'RESULT_UNKNOWN',message:'网络故障，提交结果未知。请按原幂等键核对服务端。'} as Failure;}
  let json:unknown;
  try{json=await resp.json();}catch{throw {code:'RESULT_UNKNOWN',message:'无法读取服务器回执，操作结果未知。'} as Failure;}
  if(!resp.ok){const x=json as Partial<Failure>;
    throw {code:x.code??'API_ERROR',message:x.message??'请求失败',issues:x.issues??[]} as Failure;}
  return json as T;
}
const encode=(str:string)=>encodeURIComponent(str);
const safeValue=(v:unknown)=>v===undefined?'—':typeof v==='string'?v:JSON.stringify(v);
const riskText:Record<string,string>={
  DOC_ONLY:'说明变更',ADDITIVE:'结构新增',POTENTIALLY_BREAKING:'潜在破坏性',
  POLICY_REVIEW_REQUIRED:'需要策略复核'
};

export function SnapshotActions({draft,validation,dirty,locked,navigate}:{
 draft:DraftBrief;validation:Report|null;dirty:boolean;locked:boolean;navigate:(hash:string)=>void;
}){
 const [items,setItems]=useState<Summary[]>([]);
 const [confirm,setConfirm]=useState(false);
 const [busy,setBusy]=useState(false);
 const [error,setError]=useState<string|null>(null);
 const [pendingKey,setPendingKey]=useState<string|null>(null);
 const [pendingRevision,setPendingRevision]=useState<number|null>(null);
 const pendingStorageKey='ontoforge-local-snapshot-pending:'+draft.draftId;
 const remember=(key:string,revision:number)=>{try{sessionStorage.setItem(pendingStorageKey,JSON.stringify({key,revision}))}catch{/* private browsing may block storage */}};
 const forget=()=>{try{sessionStorage.removeItem(pendingStorageKey)}catch{/* no persisted storage */}};
 const confirmRef=useRef<HTMLButtonElement|null>(null);
 const freezeButtonRef=useRef<HTMLButtonElement|null>(null);
 const active=validation?.status==='VALID'&&validation.validatedRevision===draft.revision;
 const supported=draft.editingContent.objectTypes.length===1&&draft.editingContent.linkTypes.length===0;
 const historyPath='#/model/packages/'+encode(draft.packageId)+'/snapshots';
 async function refresh(){
   try{
     const page=await request<Page>('/packages/'+encode(draft.packageId)+'/snapshots?pageSize=50');
     setItems(page.items);
   }catch(e){setError(errorText(e));}
 }
 useEffect(()=>{
   void refresh();
   try{
     const value=sessionStorage.getItem(pendingStorageKey);
     if(value){
       const parsed=JSON.parse(value) as {key?:string;revision?:number};
       if(typeof parsed.key==='string'&&Number.isInteger(parsed.revision)&&parsed.revision!>=1){
         setPendingKey(parsed.key);setPendingRevision(parsed.revision!);
       }
     }
   }catch{/* unavailable storage: leave pending recovery to original browser tab */}
   // eslint-disable-next-line react-hooks/exhaustive-deps
 },[draft.draftId]);
 useEffect(()=>{if(confirm){confirmRef.current?.focus()}else{freezeButtonRef.current?.focus()}},[confirm]);
 const already=items.some(x=>x.sourceRevision===draft.revision);
 const eligible=!busy&&!dirty&&!locked&&!pendingKey&&supported&&active&&!already;
 async function freeze(key:string,revision:number){
   remember(key,revision);
   setBusy(true);setError(null);
   try{
     const saved=await request<Snapshot>('/drafts/'+draft.draftId+'/snapshots','POST',
                            {expectedRevision:revision,idempotencyKey:key});
     forget();setPendingKey(null);setPendingRevision(null);
     await refresh();
     navigate('#/model/snapshots/'+saved.snapshotId);
   }catch(e){
     const x=e as Failure;
     setError(errorText(e));
     // An uncertain commit must never be followed by a new idempotency key.
     if(x.code==='RESULT_UNKNOWN'||x.code==='INVALID_RESPONSE'){
       setPendingKey(key);setPendingRevision(revision);
     }else{
       forget();setPendingKey(null);setPendingRevision(null);
       if(x.code==='SNAPSHOT_REVISION_ALREADY_FROZEN'||x.code==='DRAFT_REVISION_CONFLICT')await refresh();
     }
   }finally{setBusy(false)}
 }
 async function reconcile(){
   if(!pendingKey||pendingRevision===null)return;
   setBusy(true);setError(null);
   try{
     const original=await request<Snapshot>('/drafts/'+draft.draftId+'/snapshots/by-key/'+pendingKey);
     forget();setPendingKey(null);setPendingRevision(null);
     navigate('#/model/snapshots/'+original.snapshotId);
   }catch(e){
     const x=e as Failure;
     if(x.code==='SNAPSHOT_NOT_FOUND'){
       setError('服务端暂未找到该幂等键。如果需重试，使用原键及同一 revision，不能生成新键。');
     }else{setError(errorText(e))}
   }finally{setBusy(false)}
 }
 const complete=()=>{
   setConfirm(false);
   void freeze(crypto.randomUUID(),draft.revision);
 };
 return <section className="model-panel" aria-label="本地快照管理">
   <header className="model-panel-head"><div><h2><History size={16} style={{verticalAlign:'middle'}}/> 模型历史快照</h2>
     <p>冻结当前有效修订，形成只读基线；不是审核或正式发布。</p></div></header>
   <div className="model-metrics">
     <div><span>当前草稿 revision</span><b>{draft.revision}</b></div>
     <div><span>历史快照</span><b>{items.length}{items.length===50?'+':''}</b></div>
     <div><span>当前校验</span><b>{dirty?'编辑未保存':validation?.status??'NOT_RUN'}</b></div>
     <div><span>发布状态</span><b>未发布</b></div>
   </div>
   {!supported&&<div className="model-alert warning">当前首期只冻结恰好一个完整 ObjectType、零 LinkType；未支持资产不得静默裁剪。</div>}
   {error&&<div className="model-alert error" role="alert">{error}</div>}
   {pendingKey&&<div className="model-alert warning" role="status">RESULT_UNKNOWN · 保留请求键 {pendingKey}<br/>
     <button id="snapshot-reconcile" className="model-btn" disabled={busy} onClick={()=>void reconcile()}>按原幂等键核对冻结结果</button>
     {pendingRevision!==null&&<button className="model-btn" disabled={busy}
       onClick={()=>void freeze(pendingKey,pendingRevision)}>使用原键重试 revision {pendingRevision}</button>}
   </div>}
   <button ref={freezeButtonRef} id="snapshot-freeze" className="model-btn model-primary model-full"
     disabled={!eligible} onClick={()=>setConfirm(true)}><LockKeyhole size={15}/> 冻结本地只读快照</button>
   <button id="snapshot-history" className="model-btn model-full" onClick={()=>navigate(historyPath)}
     disabled={busy}><History size={15}/> 查看历史快照</button>
   <p className="model-help">冻结资格由服务端事务复核。当前未提供审批、发行号、生产激活或 Agent 接口。</p>
   {confirm&&<div className="snapshot-modal-backdrop" role="presentation">
     <div role="dialog" aria-modal="true" aria-labelledby="snapshot-confirm-title"
          className="snapshot-modal" onKeyDown={e=>{if(e.key==='Escape'){e.stopPropagation();setConfirm(false)}}}>
       <h2 id="snapshot-confirm-title">确认冻结当前草稿修订</h2>
       <p>此操作创建本地只读快照，不代表已审核、发布或激活。</p>
       <div className="snapshot-confirm-facts"><span>模型包</span><b>{draft.packageId}</b>
         <span>来源修订</span><b>revision {draft.revision}</b>
         <span>完整校验</span><b>{validation?.status}</b>
         <span>版本状态</span><b>LOCAL_SNAPSHOT_ONLY</b></div>
       <div className="model-actions">
         <button className="model-btn" onClick={()=>setConfirm(false)}>取消，返回编辑</button>
         <button ref={confirmRef} id="snapshot-confirm" className="model-btn model-primary" disabled={busy}
                 onClick={complete}>确认冻结当前修订</button>
       </div>
     </div>
   </div>}
 </section>;
}

export default function NativeSnapshotWorkspace({route,navigate}:{route:string;navigate:(hash:string)=>void}){
 const [loading,setLoading]=useState(true);
 const [error,setError]=useState<string|null>(null);
 const [snapshot,setSnapshot]=useState<Snapshot|null>(null);
 const [page,setPage]=useState<Page|null>(null);
 const [diff,setDiff]=useState<Diff|null>(null);
 const [from,setFrom]=useState('');
 const [to,setTo]=useState('');
 const [cursor,setCursor]=useState<string|null>(null);
 const matchDetail=route.match(/^#\/model\/snapshots\/([0-9a-f-]{36})$/i);
 const matchHistory=route.match(/^#\/model\/packages\/([^/?]+)\/snapshots$/);
 const matchDiff=route.match(/^#\/model\/snapshot-diffs\?(.+)$/);
 const pkg=matchHistory?decodeURIComponent(matchHistory[1]):null;
 useEffect(()=>{
   let cancelled=false;
   setLoading(true);setError(null);setSnapshot(null);setPage(null);setDiff(null);
   async function load(){
     try{
       if(matchDetail){const d=await request<Snapshot>('/snapshots/'+matchDetail[1]);if(!cancelled)setSnapshot(d)}
       else if(pkg){const d=await request<Page>('/packages/'+encode(pkg)+'/snapshots?pageSize=20'+
                    (cursor?'&cursor='+encode(cursor):''));if(!cancelled){setPage(d);
         if(d.items.length>1){setFrom(d.items[1].snapshotId);setTo(d.items[0].snapshotId);}}}
       else if(matchDiff){
         const q=new URLSearchParams(matchDiff[1]);
         const a=q.get('from'),b=q.get('to');
         if(!a||!b)throw {code:'MALFORMED_REQUEST',message:'请选择两份历史快照'};
         const d=await request<Diff>('/snapshot-diffs?from='+encode(a)+'&to='+encode(b));
         if(!cancelled)setDiff(d);
       }else throw {code:'MALFORMED_REQUEST',message:'不支持的快照地址'};
     }catch(e){if(!cancelled)setError(errorText(e))}
     finally{if(!cancelled)setLoading(false)}
   }
   void load();return()=>{cancelled=true};
   // eslint-disable-next-line react-hooks/exhaustive-deps
 },[route,cursor]);
 const historyLink=(packageId:string)=>'#/model/packages/'+encode(packageId)+'/snapshots';
 return <div className="model-page">
   <div className="model-head"><div>
     <div className="model-eyebrow">ONTOLOGY DESIGN / LOCAL SCHEMA SNAPSHOTS</div>
     <h1>{matchDetail?'只读模型快照':matchHistory?'历史模型快照':'模型结构差异'}</h1>
     <p>当前仅提供本地只读版本与结构风险评估。没有可信审核、发行或激活。</p>
   </div></div>
   {loading&&<section className="model-panel"><p>正在从 PostgreSQL 核验快照与摘要…</p></section>}
   {error&&<section className="model-panel"><div className="model-alert error" role="alert">{error}</div>
     <button className="model-btn" onClick={()=>navigate('#/model/packages')}>返回模型包</button></section>}
   {snapshot&&<><div className="model-actions">
       <button className="model-btn" onClick={()=>navigate(historyLink(snapshot.packageId))}><ArrowLeft size={15}/> 历史快照</button>
       <button className="model-btn" onClick={()=>navigate('#/model/drafts/'+snapshot.sourceDraftId)}>回到当前草稿</button>
     </div>
     <section className="model-panel">
       <header className="model-panel-head"><div><h2>快照 {snapshot.snapshotId}</h2>
         <p>不可变历史基线 · 未审核 · 未发布 · 未激活</p></div>
         <span className="model-status neutral">READ ONLY</span></header>
       <div className="model-metrics">
         <div><span>模型包</span><b>{snapshot.packageId}</b></div>
         <div><span>来源修订</span><b>{snapshot.sourceRevision}</b></div>
         <div><span>来源草稿</span><b>{snapshot.sourceDraftId}</b></div>
         <div><span>创建时间</span><b>{new Date(snapshot.createdAt).toLocaleString('zh-CN')}</b></div>
         <div><span>Schema</span><b>{snapshot.candidateSchemaVersion}</b></div>
         <div><span>状态</span><b>{snapshot.lifecycle}</b></div>
       </div>
       <div className="snapshot-digest"><label>已验证内容摘要 SHA-256（非 ReleaseDigest）</label>
         <code id="snapshot-sha">{snapshot.payloadSha256}</code>
         <small>Canonicalization: {snapshot.canonicalizationProfile} · 已从权威字节与 JSONB 镜像核验</small></div>
       <h3>Native Candidate（只读）</h3>
       <pre id="snapshot-content" className="snapshot-json">{JSON.stringify(snapshot.snapshotContent,null,2)}</pre>
     </section>
   </>}
   {pkg&&page&&<><div className="model-actions">
       <button className="model-btn" onClick={()=>navigate('#/model/packages')}><ArrowLeft size={15}/> 模型包列表</button>
     </div>
     <section className="model-panel"><header className="model-panel-head"><div><h2>{pkg}</h2>
        <p>历史快照只读；摘要是内容指纹，不是审批或发布证明。</p></div>
        <span className="model-status neutral">LOCAL_SNAPSHOT_ONLY</span></header>
       {page.items.length===0?<div className="model-empty"><strong>尚无已冻结历史</strong><p>先返回草稿，保存、校验后创建首份快照。</p></div>:
       <div className="model-table-wrap"><table className="model-table">
         <thead><tr><th>来源修订</th><th>Snapshot ID</th><th>SHA-256 摘要</th><th>冻结时间</th><th>操作</th></tr></thead>
         <tbody>{page.items.map(x=><tr key={x.snapshotId}><td>revision {x.sourceRevision}</td>
           <td><code>{x.snapshotId}</code></td><td><code>{x.payloadSha256.slice(0,16)}…</code></td>
           <td>{new Date(x.createdAt).toLocaleString('zh-CN')}</td>
           <td><button className="model-link" onClick={()=>navigate('#/model/snapshots/'+x.snapshotId)}>只读查看</button></td></tr>)}</tbody>
         </table></div>}
       {page.nextCursor&&<button className="model-btn" onClick={()=>setCursor(page.nextCursor)}>下一页</button>}
     </section>
     {page.items.length>=2&&<section className="model-panel"><h2>对照两份快照</h2>
       <p>按对象类型与属性身份比较；风险标签不等于下游兼容性认证。</p>
       <div className="model-form-grid">
         <div><label htmlFor="snapshot-from">基线版本 From</label><select id="snapshot-from" value={from}
                 onChange={e=>setFrom(e.target.value)}>
             {page.items.map(x=><option key={x.snapshotId} value={x.snapshotId}>revision {x.sourceRevision} · {x.snapshotId.slice(0,8)}</option>)}
           </select></div>
         <div><label htmlFor="snapshot-to">目标版本 To</label><select id="snapshot-to" value={to}
                 onChange={e=>setTo(e.target.value)}>
             {page.items.map(x=><option key={x.snapshotId} value={x.snapshotId}>revision {x.sourceRevision} · {x.snapshotId.slice(0,8)}</option>)}
           </select></div></div>
       <button id="snapshot-compare" className="model-btn model-primary snapshot-compare-btn"
         disabled={!from||!to||from===to}
         onClick={()=>navigate('#/model/snapshot-diffs?from='+encode(from)+'&to='+encode(to))}><GitCompareArrows size={15}/> 比较结构差异</button>
     </section>}
   </>}
   {diff&&<><div className="model-actions">
      <button className="model-btn" onClick={()=>navigate(historyLink(diff.packageId))}><ArrowLeft size={15}/> 返回历史</button>
     </div>
     <section className="model-panel"><header className="model-panel-head"><div>
        <h2>结构差异（{diff.changes.length} 项）</h2>
        <p>From {diff.fromSnapshotId} → To {diff.toSnapshotId}</p></div>
        <span className="model-status neutral">仅结构风险</span></header>
       <div className="model-alert warning"><ShieldAlert size={16}/> 未连接实际 Binding、消费者与 Policy 运行证据，不提供“已验证兼容”结论。</div>
       {diff.changes.length===0?<div className="model-empty compact">两个快照没有结构差异，不代表正式发布或已验证下游兼容。</div>:
       <div className="model-table-wrap"><table id="snapshot-diff-table" className="model-table">
         <thead><tr><th>对象 / 属性</th><th>变化</th><th>原值</th><th>新值</th><th>结构风险</th></tr></thead>
         <tbody>{diff.changes.map((x,i)=><tr key={x.path+x.changeKind+i}>
           <td><b>{x.objectTypeId}</b><small>{x.propertyId??'ObjectType'}</small><small>{x.path}</small></td>
           <td>{x.changeKind}</td><td>{safeValue(x.before)}</td><td>{safeValue(x.after)}</td>
           <td><span className={'snapshot-risk '+x.riskLevel.toLowerCase()}>{riskText[x.riskLevel]??x.riskLevel}</span></td>
         </tr>)}</tbody></table></div>}
     </section>
   </>}
   <div className="model-footnote"><CheckCircle2 size={15}/> 本地模型快照不是正式 SchemaRelease；禁止用于生产 Agent 决策与工业 Action。</div>
 </div>;
}
