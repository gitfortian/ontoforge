import { Input } from '../../../shared/ui/yak/input';
import { Button } from '../../../shared/ui/yak/button';
import React, {useEffect, useState} from 'react';
import {ArrowLeft, CheckCircle2, Database, FileClock, RefreshCw, ShieldAlert} from 'lucide-react';
import {authorizedFetch} from '../../../shared/api/auth';
import '../../design/modeling/model.css';
import './object-runtime.css';

type Property={id:string;apiName:string;label?:string;valueType:string;required:boolean;searchable:boolean;classification:string;enumValues?:string[]};
type TypeDescriptor={sourceRef:{kind:string;snapshotId?:string;draftId?:string;revision?:number};packageId:string;objectTypeId:string;apiName:string;label?:string;primaryKeyRef:string;properties:Property[];payloadSha256:string};
type BindingSummary={bindingId:string;packageId:string;objectTypeId:string;currentRevision:number;sourceId:string;sourceSchema:string;sourceTable:string;validationStatus:string;checkedAt:string;checkedRows:number;digest:string};
type BindingDetail={bindingId:string;revision:number;packageId:string;objectTypeId:string;snapshotId:string;payloadSha256:string;sourceId:string;sourceSchema:string;sourceTable:string;sourceSchemaFingerprint:string;sourceKey:string;identityDomainId:string;fieldMappings:Array<{propertyRef:string;sourceColumn:string;authority:string}>;digest:string;digestProfile:string;validationStatus:string;checkedAt:string;checkedRows:number;historical:boolean;createdAt:string};
type PropertyValue={propertyRef:string;valueType:string;status:'PRESENT'|'NULL'|'MISSING';value?:unknown;provenance?:{sourceId:string;schema:string;table:string;column:string;bindingId:string;bindingRevision:number;sourceReadRef:string}};
type ObjectReadResult={status:string;executionId:string;executedAt:string;typeSourceRef:{kind:string;snapshotId?:string};payloadSha256:string;objectRef:{packageId:string;objectTypeId:string;identityDomainId:string;key:{valueType:string;value:string}};properties:PropertyValue[];matchedRows:number;foldedDuplicateRows:number;selectedPropertyRefs:string[];bindingVersions:Array<{bindingId:string;revision:number;digest:string;sourceId:string;kind:string}>;sourceReadRef:{id:string;sourceId:string;schema:string;table:string;columns:string[];schemaFingerprint:string;startedAt:string;completedAt:string;transactionIsolation:string;consistencyNote:string};accessMode:string;quality:{accuracy:string;completeness:string;freshness:string;answerScope:string}};
type ApiError={code?:string;message?:string;fieldPath?:string;executionId?:string;httpStatus?:number};
const API='/api/runtime';
const messageOf=(error:unknown)=>{const e=error as ApiError;return (e.code?e.code+' · ':'')+(e.message||'请求失败，请保留当前条件后重试。');};
async function request<T>(path:string,init:RequestInit={}):Promise<T>{
  let response:Response;
  try{response=await authorizedFetch(path,{cache:'no-store',...init});}
  catch{throw {code:'NETWORK_UNAVAILABLE',message:'网络不可用；当前读取条件已保留。'} as ApiError;}
  let body:unknown;
  try{body=await response.json();}catch{throw {code:'INVALID_RESPONSE',message:'服务器返回了无法解析的响应。',httpStatus:response.status} as ApiError;}
  if(!response.ok){const error=body as ApiError;throw {...error,httpStatus:response.status} as ApiError;}
  return body as T;
}
const normalizeType=(value:string)=>value.trim().replace(/-/g,'_').toUpperCase();
const keyWireType=(property:Property|undefined)=>{
  if(!property)return '';
  const type=normalizeType(property.valueType);
  if(type==='STRING'||type==='TEXT')return 'STRING';
  if(type==='INTEGER'||type==='INT'||type==='LONG')return 'INTEGER';
  return '';
};
const valueText=(property:PropertyValue)=>{
  if(property.status==='NULL')return 'SQL NULL';
  if(property.status==='MISSING')return '未映射';
  if(property.value===false)return 'false';
  if(property.value===true)return 'true';
  if(property.value===0)return '0';
  if(property.value===null||property.value===undefined)return '—';
  return typeof property.value==='string'?property.value:JSON.stringify(property.value);
};
const errorHint=(error:ApiError)=>{
  switch(error.code){
    case 'OBJECT_NOT_FOUND':return '来源读取已成功，但该身份域中没有匹配记录。核对业务键；系统不会猜测其他身份域。';
    case 'SOURCE_UNAVAILABLE':case 'SOURCE_TIMEOUT':return '来源未完成读取，因此不能判断对象是否存在。检查连接后重试；不要把失败当作空对象。';
    case 'OBJECT_IDENTITY_CONFLICT':case 'PROPERTY_VALUE_CONFLICT':return '读取已终止且未返回部分对象。请核查来源主键、重复记录与权威映射。';
    case 'BINDING_SCHEMA_DRIFT':return '来源结构与已保存 revision 不一致。返回 M5 重新核验和修复映射；不会按同名列自动重绑。';
    case 'FORBIDDEN':return '服务端在读取来源之前拒绝了本次访问。请确认模型包和 Snapshot 的读取授权。';
    case 'INVALID_OBJECT_KEY':return 'STRING 保留原始大小写与空白；INTEGER 使用规范十进制形式。系统不会静默 trim 或隐式转换。';
    default:return '本次读取没有产生可用对象载荷。修正请求或绑定后再试。';
  }
};

export default function ObjectRuntimeWorkspace({route,navigate}:{route:string;navigate:(hash:string)=>void}){
  const [packageId,setPackageId]=useState('');
  const [packageInput,setPackageInput]=useState('');
  const [bindings,setBindings]=useState<BindingSummary[]>([]);
  const [listBusy,setListBusy]=useState(false);
  const [listError,setListError]=useState<ApiError|null>(null);
  const [binding,setBinding]=useState<BindingDetail|null>(null);
  const [descriptor,setDescriptor]=useState<TypeDescriptor|null>(null);
  const [keyValue,setKeyValue]=useState('');
  const [selectedRefs,setSelectedRefs]=useState<string[]>([]);
  const [result,setResult]=useState<ObjectReadResult|null>(null);
  const [error,setError]=useState<ApiError|null>(null);
  const [busy,setBusy]=useState(false);

  async function loadBindings(pkg=packageInput){
    if(!pkg.trim()){setListError({code:'PACKAGE_REQUIRED',message:'请输入有权访问的模型包 ID。'});setBindings([]);return;}
    setListBusy(true);setListError(null);setBindings([]);setBinding(null);setDescriptor(null);setResult(null);setError(null);
    try{
      const rows=await request<BindingSummary[]>(API+'/bindings?packageId='+encodeURIComponent(pkg.trim()));
      setPackageId(pkg.trim());setPackageInput(pkg.trim());setBindings(rows);
      if(rows.length===0)setListError({code:'NO_BINDINGS',message:'当前模型包没有已保存的通用 Binding revision。请先从 M4 完成类型定义，再到 M5 预览、校验并保存绑定。'});
    }catch(e){setListError(e as ApiError);}
    finally{setListBusy(false);}
  }

  async function openBinding(id:string,revision?:number){
    setBusy(true);setError(null);setResult(null);setBinding(null);setDescriptor(null);
    try{
      const suffix=revision?'?revision='+revision:'';
      const loaded=await request<BindingDetail>(API+'/bindings/'+encodeURIComponent(id)+suffix);
      if(loaded.validationStatus!=='VALID')throw {code:'BINDING_UNAVAILABLE',message:'该 Binding revision 未通过校验，不能读取对象。'} as ApiError;
      const type=await request<TypeDescriptor>('/api/model/type-sources/snapshots/'+encodeURIComponent(loaded.snapshotId)+'/types/'+encodeURIComponent(loaded.objectTypeId));
      if(type.packageId!==loaded.packageId||type.objectTypeId!==loaded.objectTypeId||type.payloadSha256!==loaded.payloadSha256){
        throw {code:'BINDING_SNAPSHOT_MISMATCH',message:'Binding revision 与不可变 Snapshot 的类型摘要不一致。'} as ApiError;
      }
      setBinding(loaded);setDescriptor(type);setPackageId(loaded.packageId);setPackageInput(loaded.packageId);
      setSelectedRefs(type.properties.map(property=>property.id));setKeyValue('');
    }catch(e){setError(e as ApiError);}
    finally{setBusy(false);}
  }

  useEffect(()=>{
    const params=new URLSearchParams(route.split('?')[1]||'');
    const pkg=params.get('packageId')||'';
    const id=params.get('bindingId')||'';
    const revisionText=params.get('bindingRevision');
    setPackageId(pkg);setPackageInput(pkg);setBindings([]);setBinding(null);setDescriptor(null);
    setSelectedRefs([]);setKeyValue('');setResult(null);setError(null);setListError(null);
    if(id){
      const revision=revisionText&&/^[1-9]\d*$/.test(revisionText)?Number(revisionText):undefined;
      void openBinding(id,revision);
    }else if(pkg){
      void loadBindings(pkg);
    }
  },[route]);

  function chooseBinding(item:BindingSummary){
    navigate('#/mvp/objects?packageId='+encodeURIComponent(item.packageId)+'&bindingId='+encodeURIComponent(item.bindingId)+'&bindingRevision='+item.currentRevision);
  }

  async function readObject(){
    if(!binding||!descriptor)return;
    const primary=descriptor.properties.find(property=>property.id===descriptor.primaryKeyRef);
    const valueType=keyWireType(primary);
    if(!valueType){setError({code:'UNSUPPORTED_IDENTITY_STRATEGY',message:'当前对象主键不是首版支持的单列 STRING / INTEGER。'});setResult(null);return;}
    setBusy(true);setError(null);setResult(null);
    try{
      const payload={bindingId:binding.bindingId,bindingRevision:binding.revision,key:{valueType,value:keyValue},propertyRefs:selectedRefs};
      const value=await request<ObjectReadResult>(API+'/objects/read',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
      setResult(value);
    }catch(e){setError(e as ApiError);}
    finally{setBusy(false);}
  }

  function changeBinding(){
    navigate('#/mvp/objects?packageId='+encodeURIComponent(packageId));
  }

  const primary=descriptor?.properties.find(property=>property.id===descriptor.primaryKeyRef);
  const keyType=keyWireType(primary);
  const nameValue=result&&descriptor?result.properties.find(property=>property.propertyRef!==descriptor.primaryKeyRef&&property.status==='PRESENT'&&typeof property.value==='string'):undefined;
  const nameDescriptor=nameValue&&descriptor?descriptor.properties.find(property=>property.id===nameValue.propertyRef):undefined;
  const objectName=nameValue?String(nameValue.value):(descriptor?.label||descriptor?.apiName||'对象');
  const objectRefText=result?('package='+result.objectRef.packageId+' / type='+result.objectRef.objectTypeId+' / identityDomain='+result.objectRef.identityDomainId+' / key='+result.objectRef.key.value):'';

  return <div className="model-page object-runtime-page">
    <div className="model-head">
      <div><div className="model-eyebrow">M6 / GENERIC OBJECT RUNTIME</div><h1>对象读取</h1>
        <p>根据已保存的对象类型、不可变 Snapshot 与固定 Binding revision 读取一个业务对象。身份、属性值和来源证据分别呈现；失败时不返回伪造的空对象。</p></div>
      <div className="model-actions">
        <span className="model-status valid">M6 · 按需只读</span>
        {binding&&<Button className="model-btn" onClick={changeBinding}><Database size={14}/> 更换绑定</Button>}
        <Button className="model-btn" onClick={()=>binding?void openBinding(binding.bindingId,binding.revision):void loadBindings()} disabled={busy||listBusy}><RefreshCw size={14}/> 重新核验</Button>
        <Button className="model-btn" onClick={()=>navigate('#/mvp/bindings'+(packageId?'?packageId='+encodeURIComponent(packageId):''))}><ArrowLeft size={14}/> 查看数据绑定</Button>
      </div>
    </div>

    {!binding&&<section className="model-panel object-runtime-binding-selector">
      <header className="model-panel-head"><div><h2>选择已保存的 Binding revision</h2><p>只读取通过 M5 有界校验的版本；不会从草稿、来源表名或客户端字段猜测对象映射。</p></div></header>
      <div className="object-runtime-package-search">
        <label htmlFor="object-runtime-package">模型包 ID</label>
        <Input id="object-runtime-package" value={packageInput} onChange={event=>setPackageInput(event.target.value)} placeholder="例如 manufacturing.procurement"/>
        <Button className="model-btn primary" onClick={()=>void loadBindings()} disabled={listBusy||busy||!packageInput.trim()}><Database size={14}/> 查询绑定</Button>
      </div>
      {listBusy&&<p role="status">正在读取已保存的绑定目录…</p>}
      {listError&&<div className="model-alert error" role="alert"><strong>{listError.code||'API_ERROR'} · {listError.message||'查询失败'}</strong></div>}
      {bindings.length>0&&<div className="object-runtime-binding-list"><table className="model-table"><thead><tr><th>ObjectType</th><th>来源</th><th>当前 revision</th><th>校验</th><th>操作</th></tr></thead><tbody>
        {bindings.map(item=><tr key={item.bindingId}><td><b>{item.objectTypeId}</b><small>{item.packageId}</small></td><td>{item.sourceSchema}.{item.sourceTable}</td><td>revision {item.currentRevision}</td><td><span className={'binding-chip '+(item.validationStatus==='VALID'?'valid':'required')}>{item.validationStatus}</span></td><td><Button className="model-btn primary" onClick={()=>chooseBinding(item)} disabled={item.validationStatus!=='VALID'}><Database size={13}/> 读取对象</Button></td></tr>)}
      </tbody></table></div>}
    </section>}

    {binding&&descriptor&&<>
      <section className="model-panel object-runtime-context">
        <div><span>模型包</span><strong>{binding.packageId}</strong></div>
        <div><span>ObjectType</span><strong>{descriptor.label||descriptor.apiName} · {binding.objectTypeId}</strong></div>
        <div><span>Snapshot</span><code>{binding.snapshotId}</code></div>
        <div><span>Binding revision</span><strong>{binding.revision} · {binding.validationStatus}</strong></div>
        <div><span>身份域</span><code>{binding.identityDomainId}</code></div>
      </section>

      <div className="object-runtime-grid">
        <section className="model-panel object-runtime-request">
          <header className="model-panel-head"><div><h2>读取条件</h2><p>规范键 + 明确的属性范围</p></div></header>
          <div className="object-runtime-field">
            <label htmlFor="object-runtime-key">业务键 · {keyType||'不支持的主键类型'}</label>
            <Input id="object-runtime-key" value={keyValue} onChange={event=>setKeyValue(event.target.value)} placeholder={keyType==='INTEGER'?'例如 9007199254740993':'例如 SUP-1001'} disabled={!keyType||busy} onKeyDown={event=>{if(event.key==='Enter')void readObject();}}/>
            <small>读取固定使用 Binding revision {binding.revision}。STRING 原样保留；INTEGER 不接受前导零、+ 号或 -0。</small>
          </div>
          <div className="object-runtime-property-choices">
            <div className="object-runtime-choice-head"><strong>请求属性</strong><span>{selectedRefs.length} / {descriptor.properties.length}</span></div>
            {descriptor.properties.map(property=><label key={property.id} className="object-runtime-property-choice">
              <input type="checkbox" checked={selectedRefs.includes(property.id)} onChange={event=>setSelectedRefs(old=>event.target.checked?[...old,property.id]:old.filter(ref=>ref!==property.id))} disabled={busy}/>
              <span><b>{property.label||property.apiName}</b><small>{property.apiName} · {property.id} · {property.valueType}{property.required?' · 必填':''}</small></span>
            </label>)}
          </div>
          <div className="object-runtime-actions">
            <Button id="read-object" className="model-btn primary" onClick={()=>void readObject()} disabled={busy||!keyType||keyValue.length===0}><Database size={14}/> {busy?'读取中…':'读取对象'}</Button>
            <Button className="model-btn" onClick={()=>{setKeyValue('');setResult(null);setError(null);}} disabled={busy}><RefreshCw size={14}/> 重置条件</Button>
          </div>
          {error&&<p className="object-runtime-request-status" role="status">上次读取未成功 · {error.code||'API_ERROR'}</p>}
          <p className="model-footnote"><ShieldAlert size={14}/> 不开放任意 SQL、来源列、predicate 或客户端授权上下文。服务端在读取前复核包/快照授权、Binding digest 和来源结构指纹。</p>
        </section>

        <section className="model-panel object-runtime-result">
          <header className="model-panel-head"><div><h2>对象结果</h2><p>属性状态与业务身份分开呈现</p></div>
            {result&&<span className="model-status valid">读取成功</span>}
            {error&&<span className="model-status invalid">读取终止</span>}</header>
          {error&&<div className="object-runtime-error" role="alert">
            <div className="object-runtime-error-title">{error.code==='OBJECT_NOT_FOUND'?'未找到该对象':error.code==='SOURCE_UNAVAILABLE'||error.code==='SOURCE_TIMEOUT'?'来源暂时不可用':error.code==='BINDING_SCHEMA_DRIFT'?'来源结构已变化':error.code==='FORBIDDEN'?'当前主体无权读取':'对象读取未完成'}</div>
            <p>{error.message||'请求失败。'}</p><p>{errorHint(error)}</p>
            <code>{error.httpStatus?error.httpStatus+' · ':''}{error.code||'API_ERROR'}{error.fieldPath?' · '+error.fieldPath:''}</code>
            {error.executionId&&<small>executionId · {error.executionId}</small>}
            <div className="object-runtime-actions"><Button className="model-btn" onClick={()=>void readObject()} disabled={busy||!keyType||keyValue.length===0}><RefreshCw size={14}/> 重新读取</Button><Button className="model-btn" onClick={()=>navigate('#/mvp/bindings?packageId='+encodeURIComponent(packageId))}><Database size={14}/> 查看 / 修复绑定</Button></div>
          </div>}
          {!error&&!result&&<div className="object-runtime-empty"><Database size={30}/><strong>等待读取</strong><p>输入业务键并选择需要的属性。结果只在服务端授权、来源读取和类型转换全部成功后显示。</p></div>}
          {result&&descriptor&&<>
            <div className="object-runtime-object-summary">
              <div><span className="object-runtime-type-chip">{descriptor.objectTypeId}</span><h3>{objectName}</h3><p>{nameDescriptor?.label||'业务对象'} · {descriptor.apiName}</p><code>{objectRefText}</code></div>
              <div className="object-runtime-key-summary"><span>ObjectRef.key</span><strong>{result.objectRef.key.value}</strong><small>{result.objectRef.key.valueType}</small></div>
            </div>
            <div className="object-runtime-result-metrics">
              <div><span>请求属性</span><strong>{result.properties.length} 项</strong></div>
              <div><span>来源行</span><strong>{result.matchedRows}</strong></div>
              <div><span>合并一致重复行</span><strong>{result.foldedDuplicateRows}</strong></div>
            </div>
            <div className="object-runtime-table-wrap"><table className="model-table object-runtime-property-table"><thead><tr><th>属性</th><th>类型</th><th>状态</th><th>值</th><th>来源列</th></tr></thead><tbody>
              {result.properties.map(value=>{
                const property=descriptor.properties.find(item=>item.id===value.propertyRef);
                return <tr key={value.propertyRef}><td><b>{property?.label||property?.apiName||value.propertyRef}</b><small>{value.propertyRef}</small></td><td>{value.valueType}</td><td><span className={'object-runtime-value-state '+value.status.toLowerCase()}>{value.status}</span></td><td className="object-runtime-value">{valueText(value)}</td><td className="object-runtime-source">{value.provenance?value.provenance.schema+'.'+value.provenance.table+'.'+value.provenance.column:'—'}</td></tr>;
              })}
            </tbody></table></div>
            <p className="model-footnote"><CheckCircle2 size={14}/> 本次结果在固定来源事务内完成读取；不代表跨系统强一致，也不推断业务资料的最新性。</p>
          </>}
        </section>

        <aside className="model-panel object-runtime-evidence">
          <header className="model-panel-head"><div><h2>读取证据</h2><p>版本、来源与质量分别记录</p></div></header>
          {!result&&!error&&<div className="object-runtime-evidence-empty"><FileClock size={23}/><span>执行读取后展示 executionId、Binding revision 与 sourceReadRef。失败时只保留安全错误证据。</span></div>}
          {error&&!result&&<div className="object-runtime-evidence-group"><h3>失败证据</h3><div><span>executionId</span><code>{error.executionId||'由服务端生成'}</code></div><div><span>错误码</span><code>{error.code||'API_ERROR'}</code></div><p>未返回对象载荷或来源值。</p></div>}
          {result&&<><div className="object-runtime-evidence-group"><h3>本次执行</h3><div><span>executionId</span><code>{result.executionId}</code></div><div><span>执行完成</span><code>{new Date(result.executedAt).toLocaleString()}</code></div><div><span>Access mode</span><code>{result.accessMode}</code></div></div>
            <div className="object-runtime-evidence-group"><h3>模型与绑定版本</h3><div><span>Snapshot</span><code>{result.typeSourceRef.snapshotId||'LOCAL_SNAPSHOT'}</code></div><div><span>Payload SHA-256</span><code>{result.payloadSha256}</code></div>{result.bindingVersions.map(version=><div key={version.bindingId}><span>Binding revision</span><code>{version.bindingId} · r{version.revision}</code><code>{version.digest}</code></div>)}</div>
            <div className="object-runtime-evidence-group"><h3>来源读取</h3><div><span>sourceReadRef</span><code>{result.sourceReadRef.id}</code></div><div><span>来源</span><code>{result.sourceReadRef.sourceId} · {result.sourceReadRef.schema}.{result.sourceReadRef.table}</code></div><div><span>读取列</span><code>{result.sourceReadRef.columns.join(', ')}</code></div><div><span>Schema SHA-256</span><code>{result.sourceReadRef.schemaFingerprint}</code></div><div><span>隔离级别</span><code>{result.sourceReadRef.transactionIsolation}</code></div><div><span>开始 / 完成</span><code>{new Date(result.sourceReadRef.startedAt).toLocaleTimeString()} / {new Date(result.sourceReadRef.completedAt).toLocaleTimeString()}</code></div><p>{result.sourceReadRef.consistencyNote}</p></div>
            <div className="object-runtime-quality"><h3>质量信封</h3><div><span>Accuracy</span><strong>{result.quality.accuracy}</strong></div><div><span>Completeness</span><strong>{result.quality.completeness}</strong></div><div><span>Freshness</span><strong className="unknown">{result.quality.freshness}</strong></div><div><span>Answer scope</span><strong>{result.quality.answerScope}</strong></div></div>
          </>}
        </aside>
      </div>
    </>}
  </div>;
}
