import { Input } from '../../../shared/ui/yak/input';
import { Button } from '../../../shared/ui/yak/button';
import React, {useEffect, useMemo, useRef, useState} from 'react';
import {ArrowLeft, CheckCircle2, ChevronRight, Database, Filter, RefreshCw, ShieldAlert, Sigma} from 'lucide-react';
import {authorizedFetch} from '../../../shared/api/auth';
import '../../design/modeling/model.css';
import './object-set.css';

type Property={id:string;apiName:string;label?:string;valueType:string;required:boolean;searchable:boolean;classification:string;enumValues?:string[]};
type TypeDescriptor={sourceRef:{kind:string;snapshotId?:string};packageId:string;objectTypeId:string;apiName:string;label?:string;primaryKeyRef:string;properties:Property[];payloadSha256:string};
type BindingSummary={bindingId:string;packageId:string;objectTypeId:string;currentRevision:number;sourceId:string;sourceSchema:string;sourceTable:string;validationStatus:string;checkedAt:string;checkedRows:number;digest:string};
type BindingDetail={bindingId:string;revision:number;packageId:string;objectTypeId:string;snapshotId:string;payloadSha256:string;sourceId:string;sourceSchema:string;sourceTable:string;sourceSchemaFingerprint:string;sourceKey:string;identityDomainId:string;fieldMappings:Array<{propertyRef:string;sourceColumn:string;authority:string}>;digest:string;validationStatus:string;historical:boolean};
type FilterDraft={id:number;propertyRef:string;operator:string;value:string};
type SortDraft={id:number;propertyRef:string;direction:'ASC'|'DESC'};
type PropertyValue={propertyRef:string;valueType:string;status:'PRESENT'|'NULL'|'MISSING';value?:unknown;provenance?:{sourceId:string;schema:string;table:string;column:string;bindingId:string;bindingRevision:number;sourceReadRef:string}};
type ObjectSetItem={objectRef:{packageId:string;objectTypeId:string;identityDomainId:string;key:{valueType:string;value:string}};properties:PropertyValue[]};
type QueryResult={status:string;executionId:string;executedAt:string;queryVersion:string;typeSourceRef:{kind:string;snapshotId:string};payloadSha256:string;accessMode:string;bindingVersions:Array<{bindingId:string;revision:number;digest:string;sourceId:string;kind:string}>;versionLock:{typeSourceRef:{kind:string;snapshotId:string};payloadSha256:string;bindingVersions:Array<{bindingId:string;revision:number;digest:string;sourceId:string;kind:string}>;sourceSchemaFingerprints:Array<{sourceId:string;schema:string;table:string;schemaFingerprint:string}>};sourceReadRef:{id:string;sourceId:string;schema:string;table:string;columns:string[];schemaFingerprint:string;startedAt:string;completedAt:string;transactionIsolation:string;checkpoint:{status:string;reason:string};consistencyNote:string};queryDigest:string;resultSnapshotRef:string;expiresAt:string;totalCount?:number;pageSize?:number;pageNumber?:number;nextCursor?:string;objects?:ObjectSetItem[];aggregate?:{operation:string;propertyRef?:string;valueType:string;value:string;matchedObjects:number};quality:{accuracy:string;completeness:string;freshness:string;answerScope:string};budget:{physicalRows:number;distinctObjects:number;sourceBytes:number;serializedResultBytes:number;maxPhysicalRows:number;maxSourceBytes:number;maxSerializedResultBytes:number;queryTimeoutSeconds:number}};
type ApiError={status?:string;code?:string;message?:string;fieldPath?:string;executionId?:string;httpStatus?:number};
type FailureEvidence={kind:'QUERY_EXECUTION'|'PAGE_REVALIDATION';resultSnapshotRef?:string;executionId?:string;failedPageNumber?:number;code:string;message:string;serverConfirmed:boolean};
const API='/api/runtime';
const canonicalType=(type:string)=>type.trim().replace(/-/g,'_').toUpperCase().replace(/^(TEXT|VARCHAR|CHAR)$/,'STRING').replace(/^(INT|LONG|BIGINT|SMALLINT)$/,'INTEGER').replace(/^(NUMBER|NUMERIC)$/,'DECIMAL').replace(/^BOOL$/,'BOOLEAN').replace(/^(TIMESTAMP|TIMESTAMPTZ|TIMESTAMP_WITH_TIME_ZONE|TIMESTAMP_WITHOUT_TIME_ZONE)$/,'DATETIME');
const operatorsFor=(type:string)=>{
  const value=canonicalType(type);
  if(['STRING','ENUM'].includes(value))return ['EQ','IN','PREFIX','IS_NULL'];
  if(['INTEGER','DECIMAL','DATETIME','DATE'].includes(value))return ['EQ','IN','GT','GTE','LT','LTE','IS_NULL'];
  if(['BOOLEAN','UUID'].includes(value))return ['EQ','IN','IS_NULL'];
  return ['EQ','IS_NULL'];
};
const wireType=(type:string)=>canonicalType(type);
const labelOperator=(op:string)=>({EQ:'等于',IN:'属于',GT:'大于',GTE:'大于等于',LT:'小于',LTE:'小于等于',PREFIX:'前缀匹配',IS_NULL:'是 SQL NULL'} as Record<string,string>)[op]||op;
const showValue=(value:PropertyValue)=>value.status==='NULL'?'SQL NULL':value.status==='MISSING'?'未映射':value.value===true?'true':value.value===false?'false':value.value===null||value.value===undefined?'—':String(value.value);
const errorMessage=(error:ApiError)=>(error.code?error.code+' · ':'')+(error.message||'请求失败；本次未返回部分结果。');
async function request<T>(path:string,init:RequestInit={}):Promise<T>{
  let response:Response;
  try{response=await authorizedFetch(path,{cache:'no-store',...init});}
  catch{throw {code:'NETWORK_UNAVAILABLE',message:'网络不可用；查询配置已保留。'} as ApiError;}
  let body:unknown;
  try{body=await response.json();}catch{throw {code:'INVALID_RESPONSE',message:'服务器返回了无法解析的响应。',httpStatus:response.status} as ApiError;}
  if(!response.ok)throw {...body as ApiError,httpStatus:response.status} as ApiError;
  return body as T;
}
const jsonPost=(body:unknown):RequestInit=>({method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});

export default function ObjectSetWorkspace({route,navigate}:{route:string;navigate:(hash:string)=>void}){
  const [packageInput,setPackageInput]=useState('');
  const [bindings,setBindings]=useState<BindingSummary[]>([]);
  const [binding,setBinding]=useState<BindingDetail|null>(null);
  const [descriptor,setDescriptor]=useState<TypeDescriptor|null>(null);
  const [busy,setBusy]=useState(false);
  const [loadingBindings,setLoadingBindings]=useState(false);
  const [error,setError]=useState<ApiError|null>(null);
  const [listError,setListError]=useState<ApiError|null>(null);
  const [filters,setFilters]=useState<FilterDraft[]>([]);
  const [sorts,setSorts]=useState<SortDraft[]>([]);
  const [project,setProject]=useState<string[]>([]);
  const [pageSize,setPageSize]=useState(25);
  const [mode,setMode]=useState<'PAGE'|'AGGREGATE'>('PAGE');
  const [aggregateOperation,setAggregateOperation]=useState<'COUNT_DISTINCT'|'SUM'>('COUNT_DISTINCT');
  const [aggregateProperty,setAggregateProperty]=useState('');
  const [result,setResult]=useState<QueryResult|null>(null);
  const [failureEvidence,setFailureEvidence]=useState<FailureEvidence|null>(null);
  const operationGeneration=useRef(0);

  const properties=descriptor?.properties||[];
  const propertyById=useMemo(()=>new Map<string,Property>(properties.map(p=>[p.id,p] as const)),[properties]);
  const mappedRefs=new Set(binding?.fieldMappings.map(m=>m.propertyRef)||[]);
  const numericProperties=properties.filter(p=>mappedRefs.has(p.id)&&['INTEGER','DECIMAL'].includes(canonicalType(p.valueType)));
  const filterableProperties=properties.filter(p=>p.searchable&&mappedRefs.has(p.id));
  const sortableProperties=properties.filter(p=>mappedRefs.has(p.id)&&['STRING','INTEGER','DECIMAL','BOOLEAN','ENUM','DATETIME','DATE','UUID'].includes(canonicalType(p.valueType)));
  const propertyLabel=(ref:string)=>{const p=propertyById.get(ref);return p?(p.label||p.apiName)+' · '+p.id:ref;};
  const activeFilterSummary=filters.filter(f=>f.propertyRef&&(f.operator==='IS_NULL'||f.value.trim()!==''))
    .map(f=>propertyLabel(f.propertyRef)+' '+labelOperator(f.operator)+(f.operator==='IS_NULL'?' SQL NULL':' '+f.value.trim())).join(' AND ')||'无';
  const sortSummary=sorts.map(s=>propertyLabel(s.propertyRef)+' '+s.direction).join(' → ')||'ObjectRef 稳定顺序';
  const projectSummary=project.map(propertyLabel).join('、')||'未选择';
  const outputSummary=mode==='PAGE'?'列表 · 每页 '+pageSize+' 条':aggregateOperation==='COUNT_DISTINCT'
    ?'COUNT_DISTINCT(ObjectRef)':'SUM('+propertyLabel(aggregateProperty)+')';

  function clearResultState(){setResult(null);setError(null);setFailureEvidence(null);}

  async function loadBindings(pkg=packageInput){
    const generation=++operationGeneration.current;
    if(!pkg.trim()){setLoadingBindings(false);setListError({code:'PACKAGE_REQUIRED',message:'请输入模型包 ID。'});setBindings([]);setBinding(null);setDescriptor(null);clearResultState();return;}
    setLoadingBindings(true);setListError(null);setBindings([]);setBinding(null);setDescriptor(null);clearResultState();
    try{
      const rows=await request<BindingSummary[]>(API+'/bindings?packageId='+encodeURIComponent(pkg.trim()));
      if(generation!==operationGeneration.current)return;
      setPackageInput(pkg.trim());setBindings(rows);
      if(rows.length===0)setListError({code:'NO_BINDINGS',message:'当前模型包没有已保存的 Binding。请先完成 M4 类型定义和 M5 绑定校验。'});
    }catch(e){if(generation===operationGeneration.current)setListError(e as ApiError);}
    finally{if(generation===operationGeneration.current)setLoadingBindings(false);}
  }

  async function openBinding(id:string,revision?:number){
    const generation=++operationGeneration.current;
    setBusy(true);clearResultState();setBinding(null);setDescriptor(null);
    try{
      const suffix=revision?'?revision='+revision:'';
      const loaded=await request<BindingDetail>(API+'/bindings/'+encodeURIComponent(id)+suffix);
      if(generation!==operationGeneration.current)return;
      if(loaded.validationStatus!=='VALID')throw {code:'BINDING_UNAVAILABLE',message:'该 Binding revision 未通过校验，不能执行 ObjectSet。'} as ApiError;
      const type=await request<TypeDescriptor>('/api/model/type-sources/snapshots/'+encodeURIComponent(loaded.snapshotId)+'/types/'+encodeURIComponent(loaded.objectTypeId));
      if(generation!==operationGeneration.current)return;
      if(type.packageId!==loaded.packageId||type.objectTypeId!==loaded.objectTypeId||type.payloadSha256!==loaded.payloadSha256)throw {code:'BINDING_SNAPSHOT_MISMATCH',message:'Binding 与不可变 Snapshot 的类型摘要不一致。'} as ApiError;
      setBinding(loaded);setDescriptor(type);setPackageInput(loaded.packageId);
      // Filters must target explicitly searchable properties. Do not silently
      // initialize an unsearchable property that cannot appear in the selector.
      const mappedPropertyRefs=new Set(loaded.fieldMappings.map(m=>m.propertyRef));
      const first=type.properties.find(p=>p.searchable&&mappedPropertyRefs.has(p.id));
      const primary=type.properties.find(p=>p.id===type.primaryKeyRef);
      setFilters(first?[{id:1,propertyRef:first.id,operator:operatorsFor(first.valueType)[0],value:''}]:[]);
      setSorts(primary?[{id:1,propertyRef:primary.id,direction:'ASC'}]:[]);
      setProject(type.properties.filter(p=>loaded.fieldMappings.some(m=>m.propertyRef===p.id)).map(p=>p.id));
      setAggregateProperty(type.properties.find(p=>mappedPropertyRefs.has(p.id)&&['INTEGER','DECIMAL'].includes(canonicalType(p.valueType)))?.id||'');
      setMode('PAGE');
    }catch(e){if(generation===operationGeneration.current)setError(e as ApiError);}
    finally{if(generation===operationGeneration.current)setBusy(false);}
  }

  useEffect(()=>{
    operationGeneration.current++;
    const params=new URLSearchParams(route.split('?')[1]||'');
    const pkg=params.get('packageId')||'';
    const id=params.get('bindingId')||'';
    const revText=params.get('bindingRevision');
    setPackageInput(pkg);setBindings([]);setBinding(null);setDescriptor(null);setFilters([]);setSorts([]);setProject([]);clearResultState();setListError(null);setBusy(false);setLoadingBindings(false);
    if(id){const revision=revText&&/^[1-9]\d*$/.test(revText)?Number(revText):undefined;void openBinding(id,revision);}
    else if(pkg)void loadBindings(pkg);
  },[route]);

  function updateFilter(id:number,patch:Partial<FilterDraft>){
    setFilters(old=>old.map(f=>f.id===id?{...f,...patch}:f));
    clearResultState();
  }
  function addFilter(){
    if(filters.length>=8||!filterableProperties.length)return;
    const p=filterableProperties[0];
    setFilters(old=>[...old,{id:Math.max(0,...old.map(f=>f.id))+1,propertyRef:p.id,operator:operatorsFor(p.valueType)[0],value:''}]);
    clearResultState();
  }
  function addSort(){
    if(sorts.length>=3)return;
    setSorts(old=>{
      if(old.length>=3)return old;
      const used=new Set(old.map(s=>s.propertyRef));
      const available=sortableProperties.find(p=>!used.has(p.id));
      if(!available)return old;
      return [...old,{id:Math.max(0,...old.map(s=>s.id))+1,propertyRef:available.id,direction:'ASC'}];
    });
    clearResultState();
  }
  function buildSteps(){
    const steps:Array<Record<string,unknown>>=[];
    const active=filters.filter(f=>f.propertyRef&&(f.operator==='IS_NULL'||f.value.trim()!==''));
    if(active.length){
      const conditions=active.map(f=>{
        const property=propertyById.get(f.propertyRef)!;
        if(f.operator==='IS_NULL')return {propertyRef:f.propertyRef,operator:f.operator};
        const type=wireType(property.valueType);
        if(f.operator==='IN'){
          const values=f.value.split(',').map(v=>v.trim()).filter(Boolean).map(v=>({valueType:type,value:type==='BOOLEAN'?v.toLowerCase()==='true':v}));
          return {propertyRef:f.propertyRef,operator:f.operator,values};
        }
        return {propertyRef:f.propertyRef,operator:f.operator,literal:{valueType:type,value:type==='BOOLEAN'?f.value.toLowerCase()==='true':f.value}};
      });
      steps.push({kind:'FILTER',conditions});
    }
    if(mode==='AGGREGATE'){
      steps.push(aggregateOperation==='COUNT_DISTINCT'?{kind:'AGGREGATE',operation:aggregateOperation}:{kind:'AGGREGATE',operation:aggregateOperation,propertyRef:aggregateProperty});
    }else{
      if(sorts.length)steps.push({kind:'SORT',fields:sorts.map(s=>({propertyRef:s.propertyRef,direction:s.direction}))});
      if(project.length)steps.push({kind:'PROJECT',propertyRefs:project});
      steps.push({kind:'PAGE',pageSize});
    }
    return steps;
  }
  async function executeQuery(){
    if(!binding||!descriptor)return;
    if(mode==='PAGE'&&project.length===0){setError({code:'PROJECT_REQUIRED',message:'至少选择一个结果属性。'});return;}
    if(mode==='AGGREGATE'&&aggregateOperation==='SUM'&&!aggregateProperty){setError({code:'AGGREGATE_PROPERTY_REQUIRED',message:'请选择用于 SUM 的数值属性。'});return;}
    const generation=++operationGeneration.current;
    const payload={queryVersion:'local-query-v1',rootTypeRef:{sourceRef:{kind:'LOCAL_SNAPSHOT',snapshotId:binding.snapshotId},packageId:binding.packageId,objectTypeId:binding.objectTypeId},bindingRef:{bindingId:binding.bindingId,revision:binding.revision},steps:buildSteps()};
    setBusy(true);clearResultState();
    try{
      const response=await request<QueryResult>(API+'/object-sets/query',jsonPost(payload));
      if(generation===operationGeneration.current)setResult(response);
    }catch(e){
      if(generation===operationGeneration.current){
        const failure=e as ApiError;
        setError(failure);
        setFailureEvidence({kind:'QUERY_EXECUTION',executionId:failure.executionId,code:failure.code||'QUERY_FAILED',
          message:failure.message||'查询未完成；没有可用的结果载荷。',serverConfirmed:typeof failure.httpStatus==='number'});
      }
    }finally{if(generation===operationGeneration.current)setBusy(false);}
  }
  async function nextPage(){
    const currentResult=result;
    if(!currentResult?.nextCursor)return;
    const cursor=currentResult.nextCursor;
    const generation=++operationGeneration.current;
    setBusy(true);setError(null);setFailureEvidence(null);
    try{
      const next=await request<QueryResult>(API+'/object-sets/pages',jsonPost({cursor}));
      if(generation!==operationGeneration.current)return;
      if(next.resultSnapshotRef!==currentResult.resultSnapshotRef)throw {code:'RESULT_SNAPSHOT_MISMATCH',message:'服务端返回的分页快照与当前执行不一致；已停止展示。'} as ApiError;
      setResult(next);
    }catch(e){
      if(generation===operationGeneration.current){
        const failure=e as ApiError;
        setResult(null);
        setError(failure);
        setFailureEvidence({kind:'PAGE_REVALIDATION',resultSnapshotRef:currentResult.resultSnapshotRef,
          executionId:currentResult.executionId,failedPageNumber:(currentResult.pageNumber||1)+1,
          code:failure.code||'PAGE_FAILED',message:failure.message||'后续页未能通过复核；旧结果载荷已停止展示。',
          serverConfirmed:typeof failure.httpStatus==='number'});
      }
    }finally{if(generation===operationGeneration.current)setBusy(false);}
  }
  function resetQuery(){setFilters([]);clearResultState();setError(null);setMode('PAGE');}

  return <div className="model-page object-set-page">
    <div className="model-head">
      <div><div className="model-eyebrow">M7 / GENERIC OBJECTSET QUERY</div><h1>对象集合查询</h1><p>基于已保存的 ObjectType 与 Binding revision 执行类型化筛选、稳定排序、字段投影、结果分页和有界聚合。查询执行与每页读取都重新核验来源及授权。</p></div>
      <div className="model-actions"><span className="model-status valid">M7 · 有界只读</span>{binding&&<Button className="model-btn" onClick={()=>navigate('#/mvp/object-sets?packageId='+encodeURIComponent(binding.packageId))}><Database size={14}/> 更换绑定</Button>}<Button className="model-btn" onClick={()=>binding?void openBinding(binding.bindingId,binding.revision):void loadBindings()} disabled={busy||loadingBindings}><RefreshCw size={14}/> 重新核验</Button><Button className="model-btn" onClick={()=>navigate('#/mvp/bindings'+(packageInput?'?packageId='+encodeURIComponent(packageInput):''))}><ArrowLeft size={14}/> 数据绑定</Button></div>
    </div>
    <div className="object-set-notice"><ShieldAlert size={16}/><span><strong>本机 local-query-v1</strong> · 只读白名单来源 · 单次扫描最多 10,000 行 / 8 MiB · 结果快照有效 5 分钟。新鲜度始终标记 UNKNOWN；本结果快照不代表源数据历史快照或正式索引。</span></div>

    {!binding&&error&&<div className="object-set-error" role="alert"><strong>{errorMessage(error)}</strong><p>未打开绑定，因此没有执行查询或返回对象数据。</p></div>}
    {!binding&&<section className="model-panel object-set-binding-panel">
      <header className="model-panel-head"><div><h2>选择已保存的 Binding revision</h2><p>只读取通过 M5 校验的版本；不会从草稿或客户端字段推断来源。</p></div></header>
      <div className="object-set-package-search"><label htmlFor="object-set-package">模型包 ID</label><Input id="object-set-package" value={packageInput} onChange={e=>setPackageInput(e.target.value)} placeholder="例如 manufacturing.procurement"/><Button className="model-btn primary" onClick={()=>void loadBindings()} disabled={loadingBindings||busy||!packageInput.trim()}><Database size={14}/> 查询绑定</Button></div>
      {loadingBindings&&<p role="status">正在读取已保存的 Binding 目录…</p>}
      {listError&&<div className="object-set-error" role="alert"><strong>{listError.code||'API_ERROR'} · {listError.message||'查询失败'}</strong></div>}
      {bindings.length>0&&<div className="object-set-binding-list"><table className="model-table"><thead><tr><th>ObjectType</th><th>来源</th><th>当前 revision</th><th>校验</th><th>操作</th></tr></thead><tbody>{bindings.map(item=><tr key={item.bindingId}><td><b>{item.objectTypeId}</b><small>{item.packageId}</small></td><td>{item.sourceSchema}.{item.sourceTable}</td><td>revision {item.currentRevision}</td><td><span className={'binding-chip '+(item.validationStatus==='VALID'?'valid':'required')}>{item.validationStatus}</span></td><td><Button className="model-btn primary" onClick={()=>{navigate('#/mvp/object-sets?packageId='+encodeURIComponent(item.packageId)+'&bindingId='+encodeURIComponent(item.bindingId)+'&bindingRevision='+item.currentRevision);}} disabled={item.validationStatus!=='VALID'}><ChevronRight size={13}/> 查询对象</Button></td></tr>)}</tbody></table></div>}
    </section>}

    {binding&&descriptor&&<>
      <section className="model-panel object-set-context">
        <div><span>模型包</span><strong>{binding.packageId}</strong></div><div><span>ObjectType</span><strong>{descriptor.label||descriptor.apiName} · {binding.objectTypeId}</strong></div><div><span>Binding revision</span><strong>{binding.revision} · {binding.validationStatus}</strong></div><div><span>来源</span><strong>{binding.sourceSchema}.{binding.sourceTable}</strong></div><div><span>身份域</span><code>{binding.identityDomainId}</code></div>
      </section>
      <div className="object-set-workspace-grid">
        <section className="model-panel object-set-query-panel">
          <header className="model-panel-head"><div><h2>查询设计</h2><p>配置执行条件与结果范围</p></div><Filter size={17}/></header>
          <div className="object-set-mode"><Button className={mode==='PAGE'?'active':''} onClick={()=>{setMode('PAGE');clearResultState();setError(null);}}>对象列表</Button><Button className={mode==='AGGREGATE'?'active':''} onClick={()=>{setMode('AGGREGATE');clearResultState();setError(null);}}><Sigma size={14}/> 聚合统计</Button></div>
          <div className="object-set-step"><div className="object-set-step-head"><strong>01 · 筛选</strong><span>AND · 最多 8 条</span></div>
            {filterableProperties.length===0&&<p className="object-set-hint">当前 Binding 没有同时满足“可搜索”且“已映射”的属性。请返回本体建模启用可搜索，并在 M5 完成字段映射、校验和保存后重新进入。</p>}
            {filters.map((filter,index)=>{const property=propertyById.get(filter.propertyRef);const options=property?operatorsFor(property.valueType):['EQ'];return <div className="object-set-filter-row" key={filter.id}>
              <label><span>属性</span><select value={filter.propertyRef} onChange={e=>{const p=propertyById.get(e.target.value);updateFilter(filter.id,{propertyRef:e.target.value,operator:p?operatorsFor(p.valueType)[0]:'EQ',value:''});}}>{filterableProperties.map(p=><option key={p.id} value={p.id}>{propertyLabel(p.id)}</option>)}</select></label>
              <label><span>操作</span><select value={filter.operator} onChange={e=>updateFilter(filter.id,{operator:e.target.value,value:''})}>{options.map(op=><option key={op} value={op}>{labelOperator(op)}</option>)}</select></label>
              {filter.operator!=='IS_NULL'&&<label className="object-set-filter-value"><span>{filter.operator==='IN'?'值（逗号分隔）':'值'}</span>{property&&canonicalType(property.valueType)==='BOOLEAN'?<select value={filter.value} onChange={e=>updateFilter(filter.id,{value:e.target.value})}><option value="">请选择</option><option value="true">true</option><option value="false">false</option></select>:<Input value={filter.value} onChange={e=>updateFilter(filter.id,{value:e.target.value})} placeholder={filter.operator==='IN'?'A,B,C':'输入规范值'}/>}</label>}
              <Button className="object-set-icon-button" aria-label={'删除筛选条件 '+(index+1)} onClick={()=>{setFilters(old=>old.filter(f=>f.id!==filter.id));clearResultState();}}>×</Button>
            </div>;})}
            <Button className="model-btn object-set-add" onClick={addFilter} disabled={filters.length>=8||filterableProperties.length===0}>＋ 添加筛选条件</Button>
          </div>
          {mode==='PAGE'?<>
            <div className="object-set-step" id="object-set-sort-step"><div className="object-set-step-head"><strong>02 · 排序</strong><span>最多 3 个字段 · NULLS LAST</span></div>
              {sorts.map((sort,index)=><div className="object-set-sort-row" key={sort.id}><label><span>优先级 {index+1}</span><select value={sort.propertyRef} onChange={e=>{setSorts(old=>old.map(s=>s.id===sort.id?{...s,propertyRef:e.target.value}:s));clearResultState();}}>{sortableProperties.map(p=><option key={p.id} value={p.id} disabled={sorts.some(s=>s.id!==sort.id&&s.propertyRef===p.id)}>{propertyLabel(p.id)}</option>)}</select></label><label><span>方向</span><select value={sort.direction} onChange={e=>{setSorts(old=>old.map(s=>s.id===sort.id?{...s,direction:e.target.value as 'ASC'|'DESC'}:s));clearResultState();}}><option value="ASC">升序 ASC</option><option value="DESC">降序 DESC</option></select></label><Button className="object-set-icon-button" aria-label={'删除排序 '+(index+1)} onClick={()=>{setSorts(old=>old.filter(s=>s.id!==sort.id));clearResultState();}}>×</Button></div>)}
              <Button className="model-btn object-set-add" onClick={addSort} disabled={sorts.length>=3||sortableProperties.every(p=>sorts.some(s=>s.propertyRef===p.id))}>＋ 添加排序</Button>
            </div>
            <div className="object-set-step"><div className="object-set-step-head"><strong>03 · 选择结果列</strong><span>{project.length} / {properties.length}</span></div><div className="object-set-project-grid">{properties.map(p=><label key={p.id}><input type="checkbox" checked={project.includes(p.id)} onChange={e=>{setProject(old=>e.target.checked?[...old,p.id]:old.filter(id=>id!==p.id));clearResultState();}}/><span>{p.label||p.apiName}<small>{p.id} · {p.valueType}{mappedRefs.has(p.id)?'':' · MISSING'}</small></span></label>)}</div></div>
            <div className="object-set-step"><div className="object-set-step-head"><strong>04 · 分页</strong><span>稳定结果快照</span></div><label className="object-set-page-size"><span>每页条数</span><select value={pageSize} onChange={e=>{setPageSize(Number(e.target.value));clearResultState();}}><option value={10}>10</option><option value={25}>25</option><option value={50}>50</option></select></label></div>
          </>:<div className="object-set-step"><div className="object-set-step-head"><strong>02 · 聚合统计</strong><span>不与分页/排序/投影混用</span></div><label className="object-set-aggregate-choice"><span>聚合操作</span><select value={aggregateOperation} onChange={e=>{setAggregateOperation(e.target.value as 'COUNT_DISTINCT'|'SUM');clearResultState();}}><option value="COUNT_DISTINCT">COUNT_DISTINCT（对象数）</option><option value="SUM">SUM（精确数值求和）</option></select></label>{aggregateOperation==='SUM'&&<label className="object-set-aggregate-choice"><span>数值属性</span><select value={aggregateProperty} onChange={e=>{setAggregateProperty(e.target.value);clearResultState();}}>{numericProperties.map(p=><option key={p.id} value={p.id}>{propertyLabel(p.id)}</option>)}</select></label>}<p className="object-set-hint">SUM 匹配对象中若包含 NULL/MISSING，整次聚合失败关闭，不会将缺失值默认当作 0。</p></div>}
          <div className="object-set-query-actions"><Button className="model-btn primary" onClick={()=>void executeQuery()} disabled={busy||!binding}>{busy?'执行中…':'执行查询'}</Button><Button className="model-btn" onClick={resetQuery} disabled={busy}>重置条件</Button></div>
          <div className="object-set-query-summary" aria-label="当前查询摘要">
            <strong>当前查询摘要</strong>
            <p><span>筛选</span>{activeFilterSummary}</p>
            <p><span>排序</span>{sortSummary}</p>
            <p><span>投影</span>{mode==='PAGE'?projectSummary:'聚合模式不使用投影'}</p>
            <p><span>返回</span>{outputSummary}</p>
            <p><span>版本与新鲜度</span>Binding revision {binding.revision} · freshness UNKNOWN</p>
          </div>
          {error&&<div className="object-set-error" role="alert"><strong>{errorMessage(error)}</strong><p>{error.fieldPath?'字段路径：'+error.fieldPath+'。':''}本次失败没有可用的对象/聚合载荷。修正条件、核验来源或重新执行。</p>{error.executionId&&<small>executionId · {error.executionId}</small>}</div>}
        </section>
        <section className="model-panel object-set-result-panel">
          <header className="model-panel-head"><div><h2>查询结果</h2><p>冻结执行范围与来源证据</p></div>{result&&<span className="model-status valid">SUCCESS</span>}{error&&<span className="model-status invalid">FAILED</span>}</header>
          {!result&&!error&&!failureEvidence&&<div className="object-set-empty"><Database size={30}/><strong>等待执行查询</strong><p>配置筛选、排序、投影或聚合后执行。成功空结果与执行失败分别呈现。</p></div>}
          {failureEvidence&&<div className="object-set-failure-evidence" role="status">
            <strong>失败证据已保留 · {failureEvidence.code}</strong>
            <p>{failureEvidence.kind==='PAGE_REVALIDATION'?'后续页复核失败，旧对象/聚合载荷已停止展示。':'本次查询执行未完成，没有可用的结果载荷。'} {failureEvidence.serverConfirmed?'服务端失败响应已确认。':'未收到可确认的服务端失败响应，执行状态未知。'} 请重新执行查询获取新的执行证据。</p>
            {failureEvidence.resultSnapshotRef&&<small>旧结果快照（仅证据，不含载荷） · {failureEvidence.resultSnapshotRef}</small>}
            {failureEvidence.failedPageNumber&&<small>失败页码 · {failureEvidence.failedPageNumber}</small>}
            {failureEvidence.executionId&&<small>executionId · {failureEvidence.executionId}</small>}
            <small>失败原因 · {failureEvidence.message}</small>
          </div>}
          {error&&!result&&!failureEvidence&&<div className="object-set-empty failed"><ShieldAlert size={28}/><strong>查询未完成</strong><p>没有显示部分结果。来源状态、新鲜度或授权不确定时，系统不会将失败伪装为空集合。</p></div>}
          {result&&result.aggregate&&<div className="object-set-aggregate-result"><span>{result.aggregate.operation}{result.aggregate.propertyRef?'('+propertyLabel(result.aggregate.propertyRef)+')':''}</span><strong>{result.aggregate.value}</strong><small>匹配对象：{result.aggregate.matchedObjects} · {result.aggregate.valueType}</small></div>}
          {result&&!result.aggregate&&<><div className="object-set-result-summary"><div><span>匹配对象总数</span><strong>{result.totalCount??0}</strong></div><div><span>当前页 / 总页数</span><strong>{result.pageNumber||1} / {Math.max(1,Math.ceil((result.totalCount??0)/(result.pageSize||pageSize)))}</strong></div><div><span>每页条数</span><strong>{result.pageSize||pageSize}</strong></div></div>
            {(result.objects||[]).length===0?<div className="object-set-empty"><CheckCircle2 size={28}/><strong>查询成功 · 0 条结果</strong><p>这是准确的空结果，不是来源失败或权限错误。</p></div>:<div className="object-set-table-wrap"><table className="model-table"><thead><tr><th>ObjectRef</th>{project.map(ref=><th key={ref}>{propertyLabel(ref)}</th>)}</tr></thead><tbody>{(result.objects||[]).map(item=><tr key={item.objectRef.key.valueType+':'+item.objectRef.key.value}><td><code>{item.objectRef.key.value}</code><small>{item.objectRef.objectTypeId} · {item.objectRef.identityDomainId}</small></td>{project.map(ref=>{const value=item.properties.find(p=>p.propertyRef===ref);return <td key={ref}><span className={'object-set-value '+(value?.status||'MISSING').toLowerCase()}>{value?showValue(value):'未返回'}</span></td>;})}</tr>)}</tbody></table></div>}
            <div className="object-set-page-controls"><span>同一执行快照 · {result.resultSnapshotRef}</span><Button className="model-btn primary" onClick={()=>void nextPage()} disabled={busy||!result.nextCursor}>{busy?'读取中…':'下一页 →'}</Button></div>
          </>}
          {result&&<details className="object-set-evidence" open><summary>执行证据与版本锁</summary><dl><dt>executionId</dt><dd>{result.executionId}</dd><dt>类型来源</dt><dd>{result.typeSourceRef.kind} · {result.typeSourceRef.snapshotId}</dd><dt>类型摘要</dt><dd>{result.payloadSha256}</dd><dt>访问模式</dt><dd>{result.accessMode}</dd><dt>queryDigest</dt><dd>{result.queryDigest}</dd><dt>结果快照</dt><dd>{result.resultSnapshotRef}</dd><dt>到期时间</dt><dd>{result.expiresAt}</dd><dt>Binding</dt><dd>{result.bindingVersions.map(v=>'revision '+v.revision+' · '+v.digest).join('; ')}</dd><dt>版本锁</dt><dd>{result.versionLock.sourceSchemaFingerprints.map(v=>v.schema+'.'+v.table+' · '+v.schemaFingerprint).join('; ')}</dd><dt>来源读取</dt><dd>{result.sourceReadRef.sourceId} · {result.sourceReadRef.schema}.{result.sourceReadRef.table}</dd><dt>结构指纹</dt><dd>{result.sourceReadRef.schemaFingerprint}</dd><dt>Checkpoint</dt><dd>{result.sourceReadRef.checkpoint.status} · {result.sourceReadRef.checkpoint.reason}</dd><dt>一致性</dt><dd>{result.sourceReadRef.transactionIsolation} · freshness {result.quality.freshness}</dd><dt>扫描预算</dt><dd>{result.budget.physicalRows}/{result.budget.maxPhysicalRows} 行 · {result.budget.sourceBytes}/{result.budget.maxSourceBytes} bytes · {result.budget.serializedResultBytes}/{result.budget.maxSerializedResultBytes} bytes</dd><dt>结果范围</dt><dd>{result.quality.accuracy} / {result.quality.completeness} / {result.quality.answerScope}</dd></dl><p>{result.sourceReadRef.consistencyNote}</p></details>}
        </section>
      </div>
    </>}
  </div>;
}
