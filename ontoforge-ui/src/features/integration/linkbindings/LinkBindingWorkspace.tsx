import React, {useEffect, useMemo, useRef, useState} from 'react';
import {ArrowLeft, CheckCircle2, Database, GitBranch, RefreshCw, Save, ShieldAlert} from 'lucide-react';
import {authorizedFetch} from '../../../shared/api/auth';
import '../../design/modeling/model.css';
import './link-binding.css';

type LinkType={id:string;apiName:string;label?:string;sourceTypeRef:string;targetTypeRef:string;cardinality:string;evidenceRequired:boolean};
type Snapshot={snapshotId:string;packageId:string;payloadSha256:string;snapshotContent:{linkTypes:LinkType[]}};
type BindingSummary={bindingId:string;packageId:string;objectTypeId:string;currentRevision:number;sourceTable:string};
type Binding={bindingId:string;revision:number;packageId:string;objectTypeId:string;snapshotId:string;sourceTable:string;sourceKey:string;sourceId:string;sourceSchema:string;sourceSchemaFingerprint:string;identityDomainId:string;digest:string;fieldMappings:Array<{propertyRef:string;sourceColumn:string}>;historical:boolean};
type SourceTable={table:string;columns:Array<{name:string;udtName:string}>;schemaFingerprint:string};
type Validation={status:string;coverage:string;checkedAt:string;checkedRows:number;orphanCount:number;nullFkCount:number;cardinality:string;referencedIdentityColumn:string;issues:Array<{code:string;message:string}>};
type LinkDetail={linkBindingId:string;revision:number;packageId:string;snapshotId:string;linkTypeId:string;sourceBindingId:string;sourceBindingRevision:number;targetBindingId:string;targetBindingRevision:number;sourceSchemaFingerprint:string;targetSchemaFingerprint:string;fkOwner:'SOURCE'|'TARGET';fkSourceColumn:string;referencedIdentityColumn:string;validationStatus:string;orphanCount:number;digest:string;historical:boolean};
type Ref={packageId:string;objectTypeId:string;identityDomainId:string;key:{valueType:string;value:string}};
type QueryResult={status:string;executionId:string;queryDigest:string;resultSnapshotRef:string;pageNumber:number;nextCursor?:string;objects:Array<{objectRef:Ref}>;links:Array<{linkTypeId:string;source:Ref;target:Ref}>;objectCount:number;linkCount:number;pathCount:number;sourceReadRefs:Array<{id:string;role:string;sourceId:string;schema:string;table:string;transactionIsolation:string;schemaFingerprint:string;checkpoint:{status:string}}> ;bindingVersions:Array<{kind:string;revision:number;digest:string}>;quality:{accuracy:string;completeness:string;answerScope:string;knownOrphanCount:number};budget:{physicalRows:number;joinCandidates:number}};
type ApiError={code?:string;message?:string;fieldPath?:string;executionId?:string};
const API='/api/runtime';
async function send<T>(path:string,init:RequestInit={}):Promise<T>{
  let response:Response;
  try{response=await authorizedFetch(path,{cache:'no-store',...init});}
  catch{throw {code:'NETWORK_UNAVAILABLE',message:'网络不可用；配置未被丢弃，请核查保存结果后再重试。'} as ApiError;}
  const data=await response.json().catch(()=>({code:'INVALID_RESPONSE',message:'服务器响应无法解析。'}));
  if(!response.ok)throw data as ApiError;
  return data as T;
}
const post=(body:unknown,method:'POST'|'PUT'='POST'):RequestInit=>({method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
const msg=(error:unknown)=>{const e=error as ApiError;return (e.code?e.code+' · ':'')+(e.message||'当前操作未完成，请检查证据与修复建议。');};
function uuid(){return crypto.randomUUID();}
const keyOf=(ref:Ref)=>ref.objectTypeId+' / '+ref.identityDomainId.slice(0,8)+' / '+ref.key.value;
const short=(v:string)=>v.length>20?v.slice(0,12)+'…'+v.slice(-5):v;

export default function LinkBindingWorkspace({route,navigate,onDirtyChange}:{route:string;navigate:(hash:string)=>void;onDirtyChange:(dirty:boolean)=>void}){
  const params=new URLSearchParams(route.split('?')[1]||'');
  const [packageId,setPackageId]=useState(params.get('packageId')||'');
  const [snapshotId,setSnapshotId]=useState(params.get('snapshotId')||'');
  const [snapshot,setSnapshot]=useState<Snapshot|null>(null);
  const [typeId,setTypeId]=useState(params.get('linkTypeId')||'');
  const [bindings,setBindings]=useState<BindingSummary[]>([]);
  const [source,setSource]=useState<Binding|null>(null);
  const [target,setTarget]=useState<Binding|null>(null);
  const [tables,setTables]=useState<SourceTable[]>([]);
  const [fkOwner,setFkOwner]=useState<'SOURCE'|'TARGET'>('TARGET');
  const [fkColumn,setFkColumn]=useState('');
  const [revision,setRevision]=useState<LinkDetail|null>(null);
  const [saved,setSaved]=useState<LinkDetail[]>([]);
  const [validation,setValidation]=useState<Validation|null>(null);
  const [direction,setDirection]=useState<'OUT'|'IN'>('OUT');
  const [rootValue,setRootValue]=useState('');
  const [result,setResult]=useState<QueryResult|null>(null);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [dirty,setDirty]=useState(false);
  const nextOp=useRef<string|null>(null);
  const operation=useRef(0);
  const currentLink=snapshot?.snapshotContent.linkTypes.find(t=>t.id===typeId);
  const owner=fkOwner==='SOURCE'?source:target;
  const ownerTable=tables.find(t=>t.table===owner?.sourceTable);
  const sourceMatches=bindings.filter(b=>b.objectTypeId===currentLink?.sourceTypeRef);
  const targetMatches=bindings.filter(b=>b.objectTypeId===currentLink?.targetTypeRef);
  const selectedRoot=direction==='OUT'?source:target;
  const selectedDestination=direction==='OUT'?target:source;
  const canValidate=Boolean(snapshot&&currentLink&&source&&target&&fkColumn&&ownerTable&&
    source.snapshotId===snapshot.snapshotId&&target.snapshotId===snapshot.snapshotId);
  const canSave=canValidate&&Boolean(validation)&&['VALID','VALID_WITH_ORPHANS'].includes(validation!.status);
  function changed(){setValidation(null);setResult(null);setError('');setDirty(true);onDirtyChange(true);nextOp.current=null;}
  const navigateBack=()=>navigate('#/mvp/object-sets?packageId='+encodeURIComponent(packageId));

  async function loadContext(){
    if(!packageId.trim()||!snapshotId.trim()){setError('需要模型包与已冻结的 Local Snapshot ID。');return;}
    const gen=++operation.current;setBusy(true);setError('');setNotice('');setResult(null);setValidation(null);
    try{
      const [snap,list,catalog]=await Promise.all([
        send<Snapshot>('/api/model/snapshots/'+encodeURIComponent(snapshotId.trim())),
        send<BindingSummary[]>(API+'/bindings?packageId='+encodeURIComponent(packageId.trim())),
        send<SourceTable[]>(API+'/sources/local-pg-sandbox/tables?packageId='+encodeURIComponent(packageId.trim()))
      ]);
      if(operation.current!==gen)return;
      if(snap.packageId!==packageId.trim())throw {code:'SNAPSHOT_PACKAGE_MISMATCH',message:'Snapshot 不属于指定模型包。'};
      const entries=await Promise.all(list.map(async b=>send<Binding>(API+'/bindings/'+b.bindingId)));
      if(operation.current!==gen)return;
      setSnapshot(snap);setBindings(list);setTables(catalog);
      const link=snap.snapshotContent.linkTypes.find(l=>l.id===typeId.trim())||snap.snapshotContent.linkTypes[0];
      setTypeId(link?.id||'');
      const firstSource=entries.find(b=>b.objectTypeId===link?.sourceTypeRef&&b.snapshotId===snap.snapshotId)||null;
      const firstTarget=entries.find(b=>b.objectTypeId===link?.targetTypeRef&&b.snapshotId===snap.snapshotId)||null;
      setSource(firstSource);setTarget(firstTarget);
      const recommended=link?.cardinality==='MANY_TO_ONE'?'SOURCE':'TARGET';
      setFkOwner(recommended);
      setFkColumn('');
      const existing=await send<LinkDetail[]>(API+'/link-bindings?packageId='+encodeURIComponent(packageId.trim()));
      if(operation.current!==gen)return;
      const active=existing.find(b=>b.linkTypeId===link?.id&&b.snapshotId===snap.snapshotId)||null;
      setSaved(existing.filter(b=>b.snapshotId===snap.snapshotId));
      setRevision(active);setDirty(false);onDirtyChange(false);
      setNotice(active?'已读取当前不可变修订；可以继续查询，修改后需重新 FULL 校验。':'请选择两端 Binding 的版本、FK 列，先 FULL 校验再保存。');
    }catch(e){if(gen===operation.current)setError(msg(e));}
    finally{if(gen===operation.current)setBusy(false);}
  }
  useEffect(()=>{
    if(packageId&&snapshotId)void loadContext();
    return ()=>{operation.current++;};
    // eslint-disable-next-line react-hooks/exhaustive-deps
  },[route]);

  async function chooseBinding(id:string,side:'SOURCE'|'TARGET'){
    if(!id)return;
    setBusy(true);
    try{
      const detail=await send<Binding>(API+'/bindings/'+encodeURIComponent(id));
      if(side==='SOURCE')setSource(detail);else setTarget(detail);
      changed();
    }catch(e){setError(msg(e));}finally{setBusy(false);}
  }
  async function openVersion(item:LinkDetail){
    setBusy(true);setError('');setResult(null);
    try{
      const [s,t]=await Promise.all([
        send<Binding>(API+'/bindings/'+item.sourceBindingId+'?revision='+item.sourceBindingRevision),
        send<Binding>(API+'/bindings/'+item.targetBindingId+'?revision='+item.targetBindingRevision)
      ]);
      setSource(s);setTarget(t);setFkOwner(item.fkOwner);setFkColumn(item.fkSourceColumn);
      setRevision(item);setValidation(null);setDirty(false);onDirtyChange(false);
      setNotice('已明确重开不可变 revision '+item.revision+'；历史能否执行仍以服务端当前资格为准。');
    }catch(e){setError(msg(e));}finally{setBusy(false);}
  }
  function payload(){
    if(!snapshot||!source||!target)return null;
    return {
      contractVersion:'local-link-binding-v1',
      modelSourceRef:{kind:'LOCAL_SNAPSHOT',snapshotId:snapshot.snapshotId},
      packageId:snapshot.packageId,linkTypeId:typeId,
      sourceBindingRef:{bindingId:source.bindingId,revision:source.revision,digest:source.digest},
      targetBindingRef:{bindingId:target.bindingId,revision:target.revision,digest:target.digest},
      mode:'FOREIGN_KEY',fkOwner,fkSourceColumn:fkColumn,
      expectedRevision:revision?.revision||0,
      idempotencyKey:nextOp.current||uuid(),
      expectedSchemaFingerprints:{source:source.sourceSchemaFingerprint,target:target.sourceSchemaFingerprint},
      validationScope:{kind:'VALIDATION_SCOPE',coverage:'FULL'}
    };
  }
  async function validate(){
    const body=payload();if(!body)return;
    setBusy(true);setError('');setValidation(null);setResult(null);
    try{
      const v=await send<Validation>(API+'/link-bindings/validate',post(body));
      setValidation(v);setNotice('FULL 校验完成；结果未持久化。保存时后端还会重新校验。');
    }catch(e){setError(msg(e));}
    finally{setBusy(false);}
  }
  async function save(){
    const body=payload();if(!body||!canSave)return;
    if(!nextOp.current)nextOp.current=body.idempotencyKey;
    const isUpdate=Boolean(revision);
    setBusy(true);setError('');
    try{
      const detail=await send<LinkDetail>(API+(isUpdate?'/link-bindings/'+revision!.linkBindingId:'/link-bindings'),
        post({...body,idempotencyKey:nextOp.current},isUpdate?'PUT':'POST'));
      setRevision(detail);
      setSaved(old=>[detail,...old.filter(x=>x.linkBindingId!==detail.linkBindingId)]);
      setValidation(null);setDirty(false);onDirtyChange(false);nextOp.current=null;
      setNotice('不可变 LinkBinding revision '+detail.revision+' 已由服务端确认。可进行真实 OUT / IN 查询。');
    }catch(e){setError(msg(e)+' 保存结果可能 UNKNOWN；保持同一个操作 UUID，复核后才重试。');}
    finally{setBusy(false);}
  }
  async function query(){
    if(dirty){setError('仍有未保存的关系绑定草稿；请先 FULL 校验保存，或重新打开已保存修订后再执行。');return;}
    if(!revision||!selectedRoot||!selectedDestination||!rootValue.trim())return;
    setBusy(true);setError('');setResult(null);
    try{
      const keyProperty=selectedRoot.fieldMappings.find(m=>m.sourceColumn===selectedRoot.sourceKey);
      const isInt=Boolean(keyProperty&&/integer|bigint/i.test(keyProperty.propertyRef));
      const type=isInt?'INTEGER':'STRING';
      const body={
        queryVersion:'local-query-v2',
        rootTypeRef:{sourceRef:{kind:'LOCAL_SNAPSHOT',snapshotId:revision.snapshotId},
          packageId:revision.packageId,objectTypeId:selectedRoot.objectTypeId},
        rootBindingRef:{bindingId:selectedRoot.bindingId,revision:selectedRoot.revision},
        rootSelector:{kind:'OBJECT_REF',objectRef:{packageId:revision.packageId,
          objectTypeId:selectedRoot.objectTypeId,identityDomainId:selectedRoot.identityDomainId,
          key:{valueType:type,value:rootValue.trim()}}},
        steps:[{kind:'TRAVERSE',linkTypeRef:revision.linkTypeId,
          linkBindingRef:{bindingId:revision.linkBindingId,revision:revision.revision},
          direction,maxDepth:1},{kind:'PAGE',pageSize:25}]
      };
      const res=await send<QueryResult>(API+'/object-sets/traverse',post(body));
      setResult(res);setNotice('服务端真实单步查询已完成。结果成员冻结，但分页时仍会复核当前来源资格。');
    }catch(e){setError(msg(e));}
    finally{setBusy(false);}
  }
  async function nextPage(){
    if(!result?.nextCursor)return;
    const original=result;setBusy(true);setError('');
    try{
      const page=await send<QueryResult>(API+'/object-sets/traverse/pages',post({cursor:original.nextCursor}));
      if(original.resultSnapshotRef!==page.resultSnapshotRef)throw {code:'RESULT_MISMATCH',message:'结果快照不一致，旧页已清除。'};
      setResult({...page,objects:[...original.objects,...page.objects],links:[...original.links,...page.links]});
    }catch(e){setResult(null);setError(msg(e)+' 旧结果已失效；请重新执行一跳查询。');}
    finally{setBusy(false);}
  }

  return <div className="link-workbench">
    <div className="model-head"><div><div className="model-eyebrow">ONTOFORGE · M8.1 / LINK BINDING</div><h1>实例关系绑定与单步查询</h1><p>关系类型 → 双端来源 → FULL 校验 → 保存 revision → OUT/IN 查询。使用真实 API 与 PostgreSQL。</p></div>
      <button className="model-btn" onClick={navigateBack}><ArrowLeft size={15}/> 返回对象集合</button></div>
    <section className="model-panel">
      <header className="model-panel-head"><div><h2>01 · 确认模型快照与关系类型</h2><p>只能引用已冻结的 LOCAL_SNAPSHOT，不能从草稿自动创建 LinkFact。</p></div><span className="model-status neutral">{revision?'r'+revision.revision:'未配置'}</span></header>
      <div className="link-two-col"><label>模型包 ID<input value={packageId} onChange={e=>setPackageId(e.target.value)} placeholder="manufacturing.quality"/></label>
        <label>Local Snapshot ID<input value={snapshotId} onChange={e=>setSnapshotId(e.target.value)} placeholder="Snapshot UUID"/></label></div>
      <button className="model-btn" disabled={busy} onClick={()=>void loadContext()}><RefreshCw size={14}/> 加载可信上下文</button>
      {snapshot&&<><div className="link-two-col"><label>LinkType<select value={typeId} onChange={e=>{setTypeId(e.target.value);changed();}}>{snapshot.snapshotContent.linkTypes.map(l=><option key={l.id} value={l.id}>{l.label||l.apiName} · {l.id}</option>)}</select></label>
        <div className="link-summary"><b>{currentLink?.sourceTypeRef||'—'} → {currentLink?.targetTypeRef||'—'}</b><small>{currentLink?.cardinality||'—'} · 来自不可变模型快照</small></div></div></>}
    </section>
    {snapshot&&currentLink&&<section className="model-panel">
      <header className="model-panel-head"><div><h2>02 · 锁定双端 Binding 与外键</h2><p>不能手工输入 SQL / 被引用端 PK；服务端由 M5 版本身份键解析。</p></div><span className="model-status neutral">{dirty?'草稿已修改':'已保存上下文'}</span></header>
      <div className="link-two-col"><label>SOURCE · {currentLink.sourceTypeRef}<select value={source?.bindingId||''} onChange={e=>void chooseBinding(e.target.value,'SOURCE')}><option value="">选择已保存 M5 Binding</option>{sourceMatches.map(x=><option key={x.bindingId} value={x.bindingId}>{x.objectTypeId} · {x.sourceTable} · r{x.currentRevision}</option>)}</select></label>
        <label>TARGET · {currentLink.targetTypeRef}<select value={target?.bindingId||''} onChange={e=>void chooseBinding(e.target.value,'TARGET')}><option value="">选择已保存 M5 Binding</option>{targetMatches.map(x=><option key={x.bindingId} value={x.bindingId}>{x.objectTypeId} · {x.sourceTable} · r{x.currentRevision}</option>)}</select></label></div>
      <div className="link-two-col"><div className="link-summary">SOURCE 锁定：<b>{source?source.sourceTable+' / '+source.sourceKey+' / r'+source.revision:'未选择'}</b><small>digest {short(source?.digest||'—')}</small></div>
        <div className="link-summary">TARGET 锁定：<b>{target?target.sourceTable+' / '+target.sourceKey+' / r'+target.revision:'未选择'}</b><small>digest {short(target?.digest||'—')}</small></div></div>
      <div className="link-two-col"><label>外键所在端<select value={fkOwner} onChange={e=>{setFkOwner(e.target.value as 'SOURCE'|'TARGET');setFkColumn('');changed();}}><option value="SOURCE">SOURCE 持有 FK</option><option value="TARGET">TARGET 持有 FK</option></select></label>
        <label>外键来源列<select value={fkColumn} onChange={e=>{setFkColumn(e.target.value);changed();}}><option value="">选择受控来源列</option>{(ownerTable?.columns||[]).map(c=><option key={c.name} value={c.name}>{c.name} · {c.udtName}</option>)}</select></label></div>
      <div className="link-tip">引用端 PK：只读，由服务端按 {fkOwner==='SOURCE'?'TARGET':'SOURCE'} 的 M5 Binding.sourceKey 解析。两端不同身份域可以通过合法 FK 建立 LinkFact。</div>
    </section>}
    {snapshot&&currentLink&&<section className="model-panel">
      <header className="model-panel-head"><div><h2>03 · FULL 只读核验与 CAS 保存</h2><p>显示来源范围与质量，验证并不自动保存；保存时重新执行后端核验。</p></div><div className="model-actions"><button className="model-btn" disabled={busy||!canValidate} onClick={()=>void validate()}><CheckCircle2 size={14}/> FULL 核验</button>
        <button className="model-btn primary" disabled={busy||!canSave} onClick={()=>void save()}><Save size={14}/> 保存不可变修订</button></div></header>
      {validation&&<div className={'link-quality '+(validation.status==='VALID'?'good':'warn')}><b>{validation.status} · FULL</b><span>扫描 {validation.checkedRows} 行，NULL FK {validation.nullFkCount}，确认孤儿 {validation.orphanCount}</span><small>checkedAt {validation.checkedAt} · 引用端 PK {validation.referencedIdentityColumn}</small></div>}
      {revision&&<div className="link-quality good"><b>已保存 revision {revision.revision} · {revision.validationStatus}</b><span>历史摘要 {short(revision.digest)} · 孤儿 {revision.orphanCount}</span><small>编辑草稿不改变保存版本；读取仍以当前权限与来源为准。</small></div>}
      {saved.length>0&&<div className="link-history"><strong>历史与已保存绑定</strong>{saved.map(r=><button key={r.linkBindingId+r.revision} className="model-btn" onClick={()=>void openVersion(r)} disabled={busy}>{r.linkTypeId} · r{r.revision}{r.historical?' (历史)':''}</button>)}</div>}
    </section>}
    {revision&&<section className="model-panel">
      <header className="model-panel-head"><div><h2>04 · OUT / IN 一跳实例查询</h2><p>始终使用已持久化 revision，不使用页面未保存的 FK 草稿。</p></div><GitBranch size={17}/></header>
      <div className="link-three-col"><label>方向<select value={direction} onChange={e=>{setDirection(e.target.value as 'OUT'|'IN');setResult(null);}}><option value="OUT">OUT · SOURCE → TARGET</option><option value="IN">IN · TARGET → SOURCE</option></select></label>
        <label>根对象身份值<input value={rootValue} onChange={e=>setRootValue(e.target.value)} placeholder="例如 EQ-001 或 LOT-11"/></label>
        <div className="link-query-go"><button className="model-btn primary" disabled={busy||dirty||!rootValue.trim()} onClick={()=>void query()}>查询真实一跳</button></div></div>
      {dirty&&<div className="link-tip">未保存的 FK/端点版本不能直接执行。保存后使用新 revision，或重开原有不可变 revision 再查询。</div>}
      <div className="link-tip">当前根端点：{selectedRoot?.objectTypeId||'—'} · {selectedRoot?.identityDomainId.slice(0,8)||'—'}；输出 {selectedDestination?.objectTypeId||'—'}。root ObjectRef 的身份与查询方向由后端验证。</div>
      {result&&<><div className={'link-quality '+(result.quality.completeness==='PARTIAL'?'warn':'good')}><b>{result.status} / {result.quality.completeness}</b><span>对象 {result.objectCount} · LinkFact {result.linkCount} · 路径 {result.pathCount} · 当前页 {result.pageNumber}</span><small>answerScope {result.quality.answerScope} · 已知孤儿 {result.quality.knownOrphanCount}</small></div>
        {result.objects.length===0?<div className="model-empty compact">本次根作用域无关联对象（与查询失败不同）。</div>:<div className="link-result-list">{result.objects.map((item,i)=><div key={i} className="link-result-item"><strong>{keyOf(item.objectRef)}</strong><small>{item.objectRef.packageId} · {item.objectRef.key.valueType}</small></div>)}</div>}
        {result.nextCursor&&<button className="model-btn" disabled={busy} onClick={()=>void nextPage()}>下一页 · 保留结果快照</button>}
        <details className="link-evidence"><summary>执行证据 · 多端版本与来源</summary><p>executionId: {result.executionId}</p><p>queryDigest: {result.queryDigest}</p><p>resultSnapshotRef: {result.resultSnapshotRef}</p><p>Binding: {result.bindingVersions.map(v=>v.kind+' r'+v.revision+' '+short(v.digest)).join(' · ')}</p>{result.sourceReadRefs.map(x=><p key={x.id}>{x.role}: {x.schema}.{x.table} · {x.transactionIsolation} · fingerprint {short(x.schemaFingerprint)} · checkpoint {x.checkpoint.status}</p>)}<p>预算：扫描 {result.budget.physicalRows} / JOIN {result.budget.joinCandidates}</p></details>
      </>}
    </section>}
    {(error||notice)&&<section className={'link-feedback '+(error?'bad':'ok')} aria-live="polite">{error&&<ShieldAlert size={17}/>}<p>{error||notice}</p></section>}
    {busy&&<p className="link-loading" role="status">正在通过真实服务核验来源与版本…</p>}
    <div className="link-tip bottom"><Database size={14}/> 数据来源是服务器受控只读 PostgreSQL；未完成权限/结构校验、预算不足或执行异常不会返回旧结果。</div>
  </div>;
}
