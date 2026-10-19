import React, {useEffect, useRef, useState} from 'react';
import {ArrowRightLeft, CheckCircle2, ChevronRight, Database, GitBranch, RefreshCw, Save, ShieldAlert} from 'lucide-react';
import {authorizedFetch} from '../../../shared/api/auth';
import './link-traverse.css';

type LinkType={id:string;apiName:string;label?:string;sourceTypeRef:string;targetTypeRef:string;cardinality:string;evidenceRequired:boolean};
type Property={id:string;apiName:string;valueType:string};
type ObjectType={id:string;primaryKeyRef:string;properties:Property[]};
type Snapshot={snapshotId:string;packageId:string;payloadSha256:string;snapshotContent:{linkTypes:LinkType[];objectTypes:ObjectType[]}};
type ObjectBinding={bindingId:string;revision:number;packageId:string;objectTypeId:string;snapshotId:string;digest:string;identityDomainId:string;sourceId:string;sourceSchema:string;sourceTable:string;sourceSchemaFingerprint:string;sourceKey:string;validationStatus:string;historical:boolean};
type BindingSummary={bindingId:string;packageId:string;objectTypeId:string;currentRevision:number;validationStatus:string};
type LinkHead={id:string;packageId:string;snapshotId:string;linkTypeId:string;revision:number};
type LinkDetail={linkBindingId:string;revision:number;packageId:string;snapshotId:string;linkTypeId:string;digest:string;validationStatus:string;historical:boolean;fkOwner:'SOURCE'|'TARGET';fkSourceColumn:string;sourceBindingId:string;sourceBindingRevision:number;targetBindingId:string;targetBindingRevision:number;validationQuality:{status:string;coverage:string;checkedAt:string;scannedRows:number;matchedLinks:number;nullFkCount:number;orphanCount:number}};
type SourceColumn={name:string;udtName:string;dataType:string};
type SourceTable={table:string;schema:string;columns:SourceColumn[];schemaFingerprint:string};
type Validation={status:string;coverage:string;scannedRows:number;matchedLinks:number;nullFkCount:number;orphanCount:number;checkedAt:string};
type ObjectRef={packageId:string;objectTypeId:string;identityDomainId:string;key:{valueType:string;value:string}};
type ResultObject={objectRef:ObjectRef;properties:unknown[]};
type LinkFact={linkTypeId:string;linkBindingId:string;linkBindingRevision:number;source:ObjectRef;target:ObjectRef;sourceReadRefs:string[]};
type OneHopResult={status:'SUCCESS';executionId:string;resultSnapshotRef:string;queryVersion:string;expiresAt:string;pageNumber:number;nextCursor?:string;objects:ResultObject[];links:LinkFact[];linkCount:number;quality:{completeness:'COMPLETE'|'PARTIAL';answerScope:string;freshness:string;knownOrphanCount:number};budget:{physicalRows:number;joinCandidates:number;sourceBytes:number};bindingVersions:Array<{kind:string;revision:number;bindingId:string;digest:string}>;sourceReadRefs:Array<{id:string;role:string;schema:string;table:string;schemaFingerprint:string;checkpoint:{status:string}}>} ;
type ApiError={code?:string;message?:string;fieldPath?:string;executionId?:string;httpStatus?:number};

async function request<T>(path:string,init:RequestInit={}):Promise<T>{
  let response:Response;
  try{response=await authorizedFetch(path,{cache:'no-store',...init});}
  catch{throw {code:'NETWORK_UNAVAILABLE',message:'网络异常；编辑和幂等键仍保留，勿新建重复修订。'} as ApiError;}
  let body:unknown;
  try{body=await response.json();}catch{throw {code:'UNKNOWN_RESPONSE',message:'服务端响应无法确认。请保留当前幂等键并重试同一请求。'} as ApiError;}
  if(!response.ok)throw {...body as ApiError,httpStatus:response.status} as ApiError;
  return body as T;
}
const post=(body:unknown):RequestInit=>({method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
const put=(body:unknown):RequestInit=>({...post(body),method:'PUT'});
const message=(e:ApiError)=>[e.code,e.message||'执行失败，未显示任何结果载荷。'].filter(Boolean).join(' · ');

export default function LinkTraversalWorkspace({packageId}:{packageId:string}){
  const [expanded,setExpanded]=useState(false);
  const [busy,setBusy]=useState(false);
  const [notice,setNotice]=useState('');
  const [error,setError]=useState<ApiError|null>(null);
  const [snapshots,setSnapshots]=useState<string[]>([]);
  const [snapshotId,setSnapshotId]=useState('');
  const [snapshot,setSnapshot]=useState<Snapshot|null>(null);
  const [bindings,setBindings]=useState<ObjectBinding[]>([]);
  const [linkHeads,setLinkHeads]=useState<LinkHead[]>([]);
  const [linkTypeId,setLinkTypeId]=useState('');
  const [fkOwner,setFkOwner]=useState<'SOURCE'|'TARGET'>('TARGET');
  const [fkColumn,setFkColumn]=useState('');
  const [tables,setTables]=useState<SourceTable[]>([]);
  const [validation,setValidation]=useState<Validation|null>(null);
  const [currentLink,setCurrentLink]=useState<LinkDetail|null>(null);
  const [opKey,setOpKey]=useState('');
  const [direction,setDirection]=useState<'OUT'|'IN'>('OUT');
  const [rootKey,setRootKey]=useState('');
  const [pageSize,setPageSize]=useState(25);
  const [result,setResult]=useState<OneHopResult|null>(null);
  const generation=useRef(0);
  const reqKey=useRef('');
  const inFlight=useRef(false);

  const type=snapshot?.snapshotContent.linkTypes.find(x=>x.id===linkTypeId)||null;
  const source=bindings.find(x=>x.snapshotId===snapshotId&&x.objectTypeId===type?.sourceTypeRef)||null;
  const target=bindings.find(x=>x.snapshotId===snapshotId&&x.objectTypeId===type?.targetTypeRef)||null;
  const fkTable=tables.find(x=>x.table===(fkOwner==='SOURCE'?source:target)?.sourceTable);
  const chosenHead=linkHeads.find(x=>x.snapshotId===snapshotId&&x.linkTypeId===linkTypeId)||null;
  const root=direction==='OUT'?source:target;

  function clearExecution(){setResult(null);setError(null);}
  function invalidateDraft(){
    setValidation(null);setCurrentLink(null);setOpKey('');reqKey.current='';setNotice('');clearExecution();
  }
  function selectType(id:string){setLinkTypeId(id);setFkColumn('');invalidateDraft();}
  function selectOwner(owner:'SOURCE'|'TARGET'){setFkOwner(owner);setFkColumn('');invalidateDraft();}

  useEffect(()=>{
    generation.current++;
    setSnapshots([]);setSnapshotId('');setSnapshot(null);setBindings([]);setLinkHeads([]);
    setLinkTypeId('');setValidation(null);setCurrentLink(null);setResult(null);setError(null);
    setNotice('');reqKey.current='';setOpKey('');
  },[packageId]);

  async function refresh(){
    if(!packageId.trim())return;
    const g=++generation.current;
    setBusy(true);setError(null);setNotice('');setValidation(null);setCurrentLink(null);setResult(null);
    try{
      const [m5,heads,available]=await Promise.all([
        request<BindingSummary[]>('/api/runtime/bindings?packageId='+encodeURIComponent(packageId)),
        request<LinkHead[]>('/api/runtime/link-bindings?packageId='+encodeURIComponent(packageId)),
        request<SourceTable[]>('/api/runtime/sources/local-pg-sandbox/tables?packageId='+encodeURIComponent(packageId))
      ]);
      const details=await Promise.all(m5.filter(x=>x.validationStatus==='VALID').map(x=>
        request<ObjectBinding>('/api/runtime/bindings/'+encodeURIComponent(x.bindingId)+'?revision='+x.currentRevision)
      ));
      if(g!==generation.current)return;
      const ids=Array.from(new Set(details.map(d=>d.snapshotId)));
      setBindings(details);setLinkHeads(heads);setTables(available);setSnapshots(ids);
      const selected=ids.includes(snapshotId)?snapshotId:ids[0]||'';
      if(!selected){setSnapshot(null);setSnapshotId('');setNotice('尚无已保存且通过 FULL 校验的 Object Binding，请先完成 M5。');return;}
      await loadSnapshot(selected,g);
    }catch(e){if(g===generation.current)setError(e as ApiError);}
    finally{if(g===generation.current)setBusy(false);}
  }
  async function loadSnapshot(id:string,g=++generation.current){
    setSnapshotId(id);setSnapshot(null);setLinkTypeId('');setFkColumn('');invalidateDraft();
    try{
      const source=await request<Snapshot>('/api/model/snapshots/'+encodeURIComponent(id));
      if(g!==generation.current)return;
      if(source.packageId!==packageId)throw {code:'SNAPSHOT_PACKAGE_MISMATCH',message:'快照不属于当前模型包。'} as ApiError;
      setSnapshot(source);
      const candidates=source.snapshotContent.linkTypes.filter(t=>t.cardinality!=='MANY_TO_MANY');
      if(candidates[0])setLinkTypeId(candidates[0].id);
      if(candidates.length===0)setNotice('该不可变快照暂无可供 M8.1 使用的单列外键 LinkType；请先在 Native 模型中定义并冻结。');
    }catch(e){if(g===generation.current)setError(e as ApiError);}
  }
  const requestBody=(key:string)=>({
    contractVersion:'local-link-binding-v1',
    modelSourceRef:{kind:'LOCAL_SNAPSHOT',snapshotId},
    packageId,linkTypeId,
    sourceBindingRef:{bindingId:source?.bindingId,revision:source?.revision,digest:source?.digest},
    targetBindingRef:{bindingId:target?.bindingId,revision:target?.revision,digest:target?.digest},
    mode:'FOREIGN_KEY',fkOwner,fkSourceColumn:fkColumn,
    expectedRevision:chosenHead?.revision||0,idempotencyKey:key,
    expectedSchemaFingerprints:{source:source?.sourceSchemaFingerprint,target:target?.sourceSchemaFingerprint},
    validationScope:{kind:'VALIDATION_SCOPE',coverage:'FULL'}
  });
  const canValidate=Boolean(source&&target&&type&&fkColumn&&!busy);
  async function validate(){
    if(!canValidate||inFlight.current)return;
    inFlight.current=true;setBusy(true);setError(null);setNotice('');setValidation(null);clearExecution();
    try{
      const key=reqKey.current||crypto.randomUUID();reqKey.current=key;setOpKey(key);
      const v=await request<Validation>('/api/runtime/link-bindings/validate',post(requestBody(key)));
      setValidation(v);
      setNotice(v.status==='VALID_WITH_ORPHANS'
        ?'FULL 校验完成：存在真实孤儿。仅相关根的遍历结果可能为 PARTIAL。'
        :'FULL 校验完成：双端外键关联符合本次可保存资格。');
    }catch(e){setError(e as ApiError);}
    finally{inFlight.current=false;setBusy(false);}
  }
  async function save(){
    if(!validation||!['VALID','VALID_WITH_ORPHANS'].includes(validation.status)||inFlight.current)return;
    inFlight.current=true;setBusy(true);setError(null);
    const key=reqKey.current||crypto.randomUUID();reqKey.current=key;setOpKey(key);
    try{
      const saved=await request<LinkDetail>(
        chosenHead?'/api/runtime/link-bindings/'+encodeURIComponent(chosenHead.id):'/api/runtime/link-bindings',
        chosenHead?put(requestBody(key)):post(requestBody(key))
      );
      setCurrentLink(saved);
      setLinkHeads(old=>[...old.filter(x=>x.id!==saved.linkBindingId),
        {id:saved.linkBindingId,packageId,snapshotId,linkTypeId,revision:saved.revision}]);
      setNotice('已保存不可变 LinkBinding revision '+saved.revision+'。可以选择根对象执行一跳遍历。');
      reqKey.current='';setOpKey('');
    }catch(e){setError(e as ApiError);setNotice('保存回执未确认或失败。若是网络/响应异常，请保留并使用同一个操作键重试；不要创建新请求。');}
    finally{inFlight.current=false;setBusy(false);}
  }
  async function openSaved(){
    if(!chosenHead||inFlight.current)return;
    inFlight.current=true;setBusy(true);setError(null);clearExecution();
    try{
      const detail=await request<LinkDetail>('/api/runtime/link-bindings/'+chosenHead.id+'?packageId='+encodeURIComponent(packageId));
      setCurrentLink(detail);setFkOwner(detail.fkOwner);setFkColumn(detail.fkSourceColumn);
      setValidation(detail.validationQuality as Validation);
      setNotice('已重开服务器保存的关系修订与来源质量证据。');
    }catch(e){setError(e as ApiError);}
    finally{inFlight.current=false;setBusy(false);}
  }
  async function execute(){
    if(!currentLink||!root||!rootKey.trim()||inFlight.current)return;
    const output=snapshot?.snapshotContent.objectTypes.find(x=>x.id===root.objectTypeId);
    const keyType=output?.properties.find(p=>p.id===output.primaryKeyRef)?.valueType;
    if(!keyType||!['STRING','INTEGER'].includes(keyType)){
      setError({code:'UNSUPPORTED_IDENTITY_TYPE',message:'当前端点主键类型不支持，请重新选择 ObjectType。'});return;
    }
    inFlight.current=true;setBusy(true);clearExecution();
    try{
      const query={
        queryVersion:'local-query-v2',
        rootTypeRef:{sourceRef:{kind:'LOCAL_SNAPSHOT',snapshotId},packageId,objectTypeId:root.objectTypeId},
        rootBindingRef:{bindingId:root.bindingId,revision:root.revision},
        rootSelector:{kind:'OBJECT_REF',objectRef:{
          packageId,objectTypeId:root.objectTypeId,identityDomainId:root.identityDomainId,
          key:{valueType:keyType,value:rootKey.trim()}
        }},
        steps:[{kind:'TRAVERSE',linkTypeRef:linkTypeId,
          linkBindingRef:{bindingId:currentLink.linkBindingId,revision:currentLink.revision},
          direction,maxDepth:1},{kind:'PAGE',pageSize}]
      };
      const output=await request<OneHopResult>('/api/runtime/object-sets/traverse',post(query));
      setResult(output);
    }catch(e){setError(e as ApiError);setResult(null);}
    finally{inFlight.current=false;setBusy(false);}
  }
  async function nextPage(){
    if(!result?.nextCursor||inFlight.current)return;
    const current=result;
    inFlight.current=true;setBusy(true);setError(null);
    try{
      const output=await request<OneHopResult>('/api/runtime/object-sets/traverse/pages',post({cursor:current.nextCursor}));
      if(output.resultSnapshotRef!==current.resultSnapshotRef)throw {code:'RESULT_SNAPSHOT_MISMATCH',message:'冻结快照不一致，已停止展示。'} as ApiError;
      setResult(output);
    }catch(e){setResult(null);setError(e as ApiError);}
    finally{inFlight.current=false;setBusy(false);}
  }

  return <section className="model-panel m8-workspace" aria-label="关系与一跳遍历 M8.1">
    <header className="model-panel-head"><div><span className="model-eyebrow">M8.1 / LINK BINDING → ONE-HOP TRAVERSE</span>
      <h2>关系配置与一跳遍历</h2><p>基于已冻结的 Native LinkType 与双端 Object Binding，在真实受控来源上验证关系并执行 OUT / IN 查询。</p></div>
      <button className="model-btn" type="button" onClick={()=>setExpanded(x=>!x)} aria-expanded={expanded}>{expanded?'收起工作台':'展开关系工作台'} <ChevronRight size={14}/></button></header>
    {expanded&&<>
      <div className="m8-steps"><span>1 选择关系与端点</span><span>2 FULL 验证</span><span>3 保存版本</span><span>4 一跳查询</span></div>
      <div className="m8-identity"><span>模型包</span><b>{packageId||'尚未选择'}</b><button className="model-btn" type="button" onClick={()=>void refresh()} disabled={busy||!packageId.trim()}><RefreshCw size={14}/> {busy?'处理中…':'读取当前配置'}</button></div>
      {!packageId&&<p className="object-set-hint">请先在对象集合页面选择模型包，然后加载该包的关系配置。</p>}
      {error&&<div className="object-set-error" role="alert"><strong>{message(error)}</strong><p>本次失败不代表合法零关联；没有继续显示旧对象或 LinkFact 载荷。{error.fieldPath?'问题字段：'+error.fieldPath:''}</p></div>}
      {notice&&<p className="m8-note" role="status">{notice}</p>}
      <div className="m8-panel-grid">
        <div className="m8-pane">
          <h3>01 · 关系类型与两端证据</h3>
          <label>Local Snapshot<select aria-label="M8 Local Snapshot" value={snapshotId} disabled={busy||snapshots.length===0}
            onChange={e=>void loadSnapshot(e.target.value)}><option value="">请选择已保存的快照</option>{snapshots.map(x=><option key={x} value={x}>{x}</option>)}</select></label>
          <label>LinkType<select aria-label="M8 LinkType" value={linkTypeId} disabled={busy||!snapshot}
            onChange={e=>selectType(e.target.value)}><option value="">选择关系类型</option>{snapshot?.snapshotContent.linkTypes.filter(t=>t.cardinality!=='MANY_TO_MANY').map(t=><option key={t.id} value={t.id}>{t.label||t.apiName} · {t.cardinality}</option>)}</select></label>
          <div className="m8-endpoints"><div><span>SOURCE / 对象绑定</span><strong>{type?.sourceTypeRef||'未选择'}</strong><small>{source?'revision '+source.revision+' · '+source.sourceTable:'缺少通过验证的 M5 Binding'}</small></div>
            <ArrowRightLeft size={18}/><div><span>TARGET / 对象绑定</span><strong>{type?.targetTypeRef||'未选择'}</strong><small>{target?'revision '+target.revision+' · '+target.sourceTable:'缺少通过验证的 M5 Binding'}</small></div></div>
          {source&&target&&<div className="m8-digests"><span>来源摘要</span><code>{source.digest.slice(0,14)}… / {target.digest.slice(0,14)}…</code></div>}
        </div>
        <div className="m8-pane">
          <h3>02 · FOREIGN_KEY / FULL 验证</h3>
          <label>外键所在端<select aria-label="M8 FK Owner" value={fkOwner} disabled={busy}
            onChange={e=>selectOwner(e.target.value as 'SOURCE'|'TARGET')}>
            <option value="SOURCE">SOURCE → TARGET（MANY_TO_ONE / ONE_TO_ONE）</option>
            <option value="TARGET">TARGET → SOURCE（ONE_TO_MANY / ONE_TO_ONE）</option></select></label>
          <label>受控 FK 来源列<select aria-label="M8 FK Column" value={fkColumn} disabled={busy||!fkTable}
            onChange={e=>{setFkColumn(e.target.value);invalidateDraft();}}>
            <option value="">选择来源列</option>{fkTable?.columns.map(col=><option key={col.name} value={col.name}>{col.name} · {col.udtName}</option>)}</select></label>
          <p className="object-set-hint">对端权威主键由已保存的 M5 Binding 决定，不能由客户端指定。仅 FULL 验证可保存。</p>
          <div className="m8-actions"><button className="model-btn" type="button" onClick={()=>void validate()} disabled={!canValidate}><CheckCircle2 size={14}/> 执行 FULL 验证</button>
            <button className="model-btn primary" type="button" disabled={busy||!validation||!['VALID','VALID_WITH_ORPHANS'].includes(validation.status)||!source||!target}
              onClick={()=>void save()}><Save size={14}/> {chosenHead?'保存新 revision':'保存 LinkBinding'}</button>
            {chosenHead&&<button className="model-btn" type="button" onClick={()=>void openSaved()} disabled={busy}>重开已保存</button>}</div>
          {validation&&<div className="m8-validation" data-quality={validation.status}>
            <strong>{validation.status} · {validation.coverage||'FULL'}</strong>
            <span>已扫描 {validation.scannedRows} 行 · 匹配 {validation.matchedLinks} · SQL NULL {validation.nullFkCount} · 孤儿 {validation.orphanCount}</span>
          </div>}
          {opKey&&<small className="m8-operation">保存操作键（响应未知时保持不变）：<code>{opKey}</code></small>}
        </div>
      </div>
      <div className="m8-pane m8-query">
        <h3>03 · 已保存关系的一跳遍历</h3>
        {currentLink?<div className="m8-current"><CheckCircle2 size={16}/><strong>{currentLink.linkTypeId} · revision {currentLink.revision}</strong><span>{currentLink.validationStatus} · 来源检查来自不可变修订</span></div>
          :<p className="object-set-hint">先验证并保存 LinkBinding，或使用“重开已保存”。未保存的前端草稿不能参与遍历。</p>}
        <div className="m8-query-controls"><label>方向<select aria-label="M8 Traverse Direction" value={direction} onChange={e=>{setDirection(e.target.value as 'OUT'|'IN');clearExecution();}}>
          <option value="OUT">OUT · SOURCE → TARGET</option><option value="IN">IN · TARGET → SOURCE</option></select></label>
          <label>根对象主键<input aria-label="M8 Root Object Key" placeholder={direction==='OUT'?'例如 EQ-001':'例如 LOT-11'} value={rootKey} onChange={e=>{setRootKey(e.target.value);clearExecution();}}/></label>
          <label>每页<select aria-label="M8 Traverse Page Size" value={pageSize} onChange={e=>{setPageSize(Number(e.target.value));clearExecution();}}>
            <option value={10}>10</option><option value={25}>25</option><option value={50}>50</option></select></label>
          <button className="model-btn primary" type="button" disabled={!currentLink||!rootKey.trim()||busy} onClick={()=>void execute()}><GitBranch size={15}/> {busy?'执行中…':'执行一跳查询'}</button></div>
        {result&&<div className="m8-result">
          <div className="m8-result-stats"><div><span>结果质量</span><strong>{result.quality.completeness}</strong><small>{result.quality.answerScope} · Freshness {result.quality.freshness}</small></div>
            <div><span>本页 LinkFact</span><strong>{result.linkCount}</strong><small>第 {result.pageNumber} 页</small></div>
            <div><span>相关孤儿</span><strong>{result.quality.knownOrphanCount}</strong><small>仅本次根作用域</small></div></div>
          <div className="m8-links">{result.links.length===0?<div className="m8-empty"><CheckCircle2 size={20}/><strong>{result.quality.completeness==='COMPLETE'?'成功 · 0 条关系':'已知子集 · 0 条有效关系'}</strong><p>{result.quality.completeness==='COMPLETE'?'当前根确实没有可关联对象。':'当前根存在已确认的未匹配 FK；不能把 0 条有效关系解释为完整的业务世界。'}</p></div>
            :<table className="model-table"><thead><tr><th>Source ObjectRef</th><th>Target ObjectRef</th><th>LinkType</th></tr></thead><tbody>
            {result.links.map((fact,i)=><tr key={i}><td><b>{fact.source.key.value}</b><small>{fact.source.objectTypeId}</small></td><td><b>{fact.target.key.value}</b><small>{fact.target.objectTypeId}</small></td><td>{fact.linkTypeId}</td></tr>)}</tbody></table>}</div>
          <div className="m8-evidence"><strong>冻结来源与版本证据</strong><span>双端 SourceReadRefs：{result.sourceReadRefs.length} · 版本锁：{result.bindingVersions.length} · 总扫描：{result.budget.physicalRows} 行 · 查询候选：{result.budget.joinCandidates}</span>
            <small>结果快照 {result.resultSnapshotRef} · 到期 {result.expiresAt} · 不声明历史源时刻或新鲜度保证</small></div>
          {result.nextCursor&&<button className="model-btn" disabled={busy} type="button" onClick={()=>void nextPage()}>下一页 <ChevronRight size={14}/></button>}
        </div>}
      </div>
    </>}
  </section>;
}
