import React, {useEffect, useState} from 'react';
import {ArrowLeft, CheckCircle2, Database, FileClock, Plus, RefreshCw, Save, ShieldAlert} from 'lucide-react';
import {authorizedFetch} from './auth';
import './model.css';
import './binding.css';

type Property={id:string;apiName:string;label?:string;valueType:string;required:boolean;searchable:boolean;classification:string;enumValues?:string[]};
type TypeDescriptor={packageId:string;objectTypeId:string;apiName:string;label?:string;primaryKeyRef:string;properties:Property[];payloadSha256:string};
type SourceColumn={name:string;dataType:string;udtName:string;nullable:boolean;ordinalPosition:number;primaryKey:boolean;uniqueKey:boolean};
type SourceTable={sourceId:string;schema:string;table:string;columns:SourceColumn[];schemaFingerprint:string};
type FieldMapping={propertyRef:string;sourceColumn:string;authority:'AUTHORITATIVE'};
type BindingSummary={bindingId:string;packageId:string;objectTypeId:string;currentRevision:number;sourceId:string;sourceSchema:string;sourceTable:string;validationStatus:string;checkedAt:string;checkedRows:number;digest:string};
type ValidationIssue={code:string;message:string;fieldPath:string};
type Validation={status:string;checkedAt:string;checkedRows:number;schemaFingerprint:string;issues:ValidationIssue[]};
type Preview={packageId:string;snapshotId:string;objectTypeId:string;objectTypeLabel?:string;payloadSha256:string;source:SourceTable;sourceRows:Array<Record<string,unknown>>;mappedRows:Array<Record<string,unknown>>};
type BindingDetail={bindingId:string;revision:number;packageId:string;objectTypeId:string;snapshotId:string;payloadSha256:string;sourceId:string;sourceSchema:string;sourceTable:string;sourceSchemaFingerprint:string;sourceKey:string;identityDomainId:string;fieldMappings:FieldMapping[];digest:string;digestProfile:string;validationStatus:string;checkedAt:string;checkedRows:number;issues:ValidationIssue[];historical:boolean;createdAt:string};
type RevisionSummary={revision:number;digest:string;validationStatus:string;checkedAt:string;checkedRows:number;createdAt:string};
type ApiError={code?:string;message?:string;fieldPath?:string};
const API='/api/runtime';
const messageOf=(error:unknown)=>{const e=error as ApiError;return (e.code?e.code+' · ':'')+(e.message||'请求失败，请保留当前编辑后重试。');};
async function request<T>(path:string,init:RequestInit={}):Promise<T>{
  let response:Response;
  try{response=await authorizedFetch(path,{cache:'no-store',...init});}
  catch{throw {code:'NETWORK_UNAVAILABLE',message:'网络不可用；当前编辑仍保留在页面中。'} as ApiError;}
  let body:unknown;
  try{body=await response.json();}catch{throw {code:'INVALID_RESPONSE',message:'服务器返回了无法解析的响应。'} as ApiError;}
  if(!response.ok){const error=body as ApiError;throw {code:error.code||'API_ERROR',message:error.message||'请求失败',fieldPath:error.fieldPath} as ApiError;}
  return body as T;
}
const norm=(value:string)=>value.replace(/[-_]/g,'').toLowerCase();

export default function DataBindingWorkspace({route,navigate,onDirtyChange}:{route:string;navigate:(hash:string)=>void;onDirtyChange:(dirty:boolean)=>void}){
  const [packageId,setPackageId]=useState('');
  const [snapshotInput,setSnapshotInput]=useState('');
  const [typeInput,setTypeInput]=useState('');
  const [descriptor,setDescriptor]=useState<TypeDescriptor|null>(null);
  const [tables,setTables]=useState<SourceTable[]>([]);
  const [tableName,setTableName]=useState('');
  const [sourceKey,setSourceKey]=useState('');
  const [mappingState,setMappingState]=useState<Record<string,string>>({});
  const [fingerprint,setFingerprint]=useState('');
  const [preview,setPreview]=useState<Preview|null>(null);
  const [validation,setValidation]=useState<Validation|null>(null);
  const [bindings,setBindings]=useState<BindingSummary[]>([]);
  const [detail,setDetail]=useState<BindingDetail|null>(null);
  const [revisions,setRevisions]=useState<RevisionSummary[]>([]);
  const [bindingId,setBindingId]=useState('');
  const [revision,setRevision]=useState(0);
  const [editing,setEditing]=useState(false);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [dirty,setDirty]=useState(false);

  const selectedTable=tables.find(t=>t.table===tableName)||null;
  const mappings:FieldMapping[]=(descriptor?.properties||[]).filter(p=>Boolean(mappingState[p.id])).map(p=>({
    propertyRef:p.id,sourceColumn:mappingState[p.id],authority:'AUTHORITATIVE'
  }));
  const isDirty=editing&&dirty;

  async function loadBindings(pkg=packageId){
    if(!pkg.trim()){setBindings([]);return;}
    try{setBindings(await request<BindingSummary[]>(API+'/bindings?packageId='+encodeURIComponent(pkg.trim())));}
    catch(e){setError(messageOf(e));}
  }

  async function beginEditor(snapshot:string,typeId:string,existing?:BindingDetail){
    setBusy(true);setError('');setNotice('');setPreview(null);setValidation(null);
    try{
      const type=await request<TypeDescriptor>('/api/model/type-sources/snapshots/'+encodeURIComponent(snapshot)+'/types/'+encodeURIComponent(typeId));
      const sourceTables=await request<SourceTable[]>(API+'/sources/local-pg-sandbox/tables?packageId='+encodeURIComponent(type.packageId));
      if(sourceTables.length===0)throw {code:'SOURCE_TABLE_NOT_FOUND',message:'当前允许列表中没有可用的来源 BASE TABLE。'} as ApiError;
      let current=existing;
      if(!current){
        const rows=await request<BindingSummary[]>(API+'/bindings?packageId='+encodeURIComponent(type.packageId));
        const currentSummary=rows.find(row=>row.objectTypeId===type.objectTypeId);
        if(currentSummary)current=await request<BindingDetail>(API+'/bindings/'+currentSummary.bindingId);
      }
      const table=sourceTables.find(t=>t.table===(current?.sourceTable||''))||sourceTables[0];
      const key=current?.sourceKey||table.columns.find(c=>c.primaryKey)?.name||table.columns.find(c=>c.uniqueKey)?.name||'';
      const nextMappings:Record<string,string>={};
      if(current){
        current.fieldMappings.forEach(m=>nextMappings[m.propertyRef]=m.sourceColumn);
      }else{
        type.properties.forEach(p=>{
          if(p.id===type.primaryKeyRef){nextMappings[p.id]=key;return;}
          const candidate=table.columns.find(c=>norm(c.name)===norm(p.apiName)||norm(c.name)===norm(p.id));
          if(candidate)nextMappings[p.id]=candidate.name;
        });
      }
      setDescriptor(type);setPackageId(type.packageId);setSnapshotInput(snapshot);setTypeInput(typeId);
      setTables(sourceTables);setTableName(table.table);setSourceKey(key);setMappingState(nextMappings);
      setFingerprint(current?.sourceSchemaFingerprint||'');setBindingId(current?.bindingId||'');setRevision(current?.revision||0);
      setDetail(null);setRevisions([]);setEditing(true);setDirty(false);setNotice(current?'该 ObjectType 已有绑定；保存将创建不可变的新 revision。':'选择来源列并完成映射后，先预览、再校验、最后保存。');
      onDirtyChange(false);setDirty(false);
    }catch(e){setError(messageOf(e));}
    finally{setBusy(false);}
  }

  useEffect(()=>{
    const params=new URLSearchParams(route.split('?')[1]||'');
    const pkg=params.get('packageId')||'';
    const snapshot=params.get('snapshotId')||'';
    const typeId=params.get('objectTypeId')||'';
    setPackageId(pkg);setSnapshotInput(snapshot);setTypeInput(typeId);
    if(snapshot&&typeId){void beginEditor(snapshot,typeId);}
    else{setEditing(false);setDescriptor(null);setDetail(null);onDirtyChange(false);}
  },[route]);

  useEffect(()=>{if(packageId.trim())void loadBindings(packageId);},[packageId]);

  function requestBody(expectedFingerprint=fingerprint){
    if(!descriptor||!selectedTable)throw {code:'MALFORMED_REQUEST',message:'请先选择快照类型和来源表。'} as ApiError;
    return {
      snapshotId:snapshotInput,objectTypeId:descriptor.objectTypeId,sourceId:'local-pg-sandbox',
      schema:selectedTable.schema,table:selectedTable.table,sourceKey,fieldMappings:mappings,
      expectedSchemaFingerprint:expectedFingerprint||null,expectedRevision:revision
    };
  }

  async function runPreview(){
    setBusy(true);setError('');setNotice('');
    try{
      const result=await request<Preview>(API+'/bindings/preview',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(requestBody(''))});
      setPreview(result);setFingerprint(result.source.schemaFingerprint);setValidation(null);
      setNotice('有限样本预览完成。请确认原始来源值与映射后的对象属性，再运行有界校验。');
    }catch(e){setError(messageOf(e));}
    finally{setBusy(false);}
  }

  async function runValidation(){
    setBusy(true);setError('');setNotice('');
    try{
      if(!fingerprint)throw {code:'PREVIEW_REQUIRED',message:'必须先预览来源并取得结构指纹。'} as ApiError;
      const result=await request<Validation>(API+'/bindings/validate',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(requestBody())});
      setValidation(result);
      setNotice(result.status==='VALID'?'有界校验通过，可以保存当前 Binding revision。':'校验发现问题；修正映射或来源数据后重新预览与校验。');
    }catch(e){setError(messageOf(e));}
    finally{setBusy(false);}
  }

  async function saveBinding(){
    if(!validation||validation.status!=='VALID'){setError('必须先完成有界校验且结果为 VALID，才能保存。');return;}
    setBusy(true);setError('');setNotice('');
    try{
      const body=requestBody();
      const saved=await request<BindingDetail>(bindingId?API+'/bindings/'+bindingId:API+'/bindings',{
        method:bindingId?'PUT':'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)
      });
      setBindingId(saved.bindingId);setRevision(saved.revision);setDetail(saved);setEditing(false);setPreview(null);setValidation(null);
      onDirtyChange(false);setDirty(false);setNotice('绑定 revision '+saved.revision+' 已持久化。历史版本保持不可变。');
      await loadBindings(saved.packageId);
    }catch(e){setError(messageOf(e));}
    finally{setBusy(false);}
  }

  async function openBinding(id:string,rev?:number){
    setBusy(true);setError('');setNotice('');
    try{
      const loaded=await request<BindingDetail>(API+'/bindings/'+id+(rev?'?revision='+rev:''));
      const history=await request<RevisionSummary[]>(API+'/bindings/'+id+'/revisions');
      setDetail(loaded);setRevisions(history);setEditing(false);setDirty(false);onDirtyChange(false);
    }catch(e){setError(messageOf(e));}
    finally{setBusy(false);}
  }

  function discardEditor(){
    if(isDirty&&!window.confirm('当前编辑尚未保存，离开将放弃这些更改。确定继续吗？'))return;
    setEditing(false);setDescriptor(null);setPreview(null);setValidation(null);setError('');setNotice('');setDirty(false);
    onDirtyChange(false);
  }

  return <div className="model-page binding-page">
    <div className="model-head">
      <div><div className="model-eyebrow">M5 / GENERIC DATA BINDING</div><h1>数据绑定</h1>
        <p>将已冻结的 Native ObjectType 映射到受控 PostgreSQL 来源。每次保存产生不可变 revision；本模块只读来源，不执行业务写回。</p></div>
      <div className="model-actions"><span className="model-status valid">M5 · 生产实现</span><button className="model-btn" onClick={()=>void loadBindings()} disabled={busy}><RefreshCw size={14}/> 刷新目录</button></div>
    </div>

    {error&&<div className="model-alert error" role="alert"><strong>{error}</strong></div>}
    {notice&&<div className="binding-notice" role="status">{notice}</div>}

    {editing&&descriptor?<>
      <div className="binding-flow">
        <span className="active">1 选择来源</span><span className={preview?'active':''}>2 预览映射</span>
        <span className={validation?'active':''}>3 有界校验</span><span className={bindingId?'active':''}>4 保存版本</span>
      </div>
      <section className="model-panel binding-editor">
        <header className="model-panel-head"><div><h2>{bindingId?'编辑绑定 · 新建 revision':'创建通用绑定'}</h2><p>{descriptor.label||descriptor.apiName} · {descriptor.objectTypeId} · {descriptor.packageId}</p></div>
          <button className="model-btn" onClick={discardEditor} disabled={busy}><ArrowLeft size={14}/> 返回目录</button></header>
        <div className="binding-contract">
          <div><span>Local Snapshot</span><code>{snapshotInput}</code></div>
          <div><span>模型摘要</span><code>{descriptor.payloadSha256}</code></div>
          <div><span>ObjectType 主键</span><code>{descriptor.primaryKeyRef}</code></div>
          {bindingId&&<div><span>当前 revision</span><b>{revision}</b></div>}
        </div>
        <div className="binding-form-grid">
          <label>来源表<select value={tableName} onChange={e=>{setTableName(e.target.value);setFingerprint('');setPreview(null);setValidation(null);setDirty(true);setDirty(true);onDirtyChange(true);}}>
            {tables.map(t=><option key={t.table} value={t.table}>{t.schema}.{t.table} · {t.columns.length} 列</option>)}
          </select></label>
          <label>来源身份键<select value={sourceKey} onChange={e=>{setSourceKey(e.target.value);setFingerprint('');setPreview(null);setValidation(null);setDirty(true);onDirtyChange(true);}}>
            <option value="">选择主键 / 唯一列</option>{(selectedTable?.columns||[]).filter(c=>c.primaryKey||c.uniqueKey).map(c=><option key={c.name} value={c.name}>{c.name} · {c.dataType}</option>)}
          </select></label>
        </div>
        <div className="binding-map-head"><div><h3>属性映射</h3><p>主键必须映射到 primaryKeyRef；只支持 AUTHORITATIVE，不执行隐式类型转换。</p></div><span>{mappings.length} 项已配置</span></div>
        <div className="binding-map-table"><table className="model-table"><thead><tr><th>对象属性</th><th>类型</th><th>来源列</th><th>约束</th></tr></thead><tbody>
          {descriptor.properties.map(p=><tr key={p.id}><td><b>{p.label||p.apiName}</b><small>{p.apiName} · {p.id}</small></td><td>{p.valueType}</td>
            <td><select aria-label={'来源列 '+p.apiName} value={mappingState[p.id]||''} onChange={e=>{setMappingState(old=>({...old,[p.id]:e.target.value}));setPreview(null);setValidation(null);onDirtyChange(true);}}>
              <option value="">不映射</option>{(selectedTable?.columns||[]).map(c=><option key={c.name} value={c.name}>{c.name} · {c.dataType}</option>)}
            </select></td><td>{p.id===descriptor.primaryKeyRef?<span className="binding-chip required">主键</span>:p.required?<span className="binding-chip required">必填</span>:<span className="binding-chip">可选</span>}</td>
          </tr>)}
        </tbody></table></div>
        <div className="binding-actions">
          <button className="model-btn" onClick={()=>void runPreview()} disabled={busy||!sourceKey||mappings.length<3}><Database size={14}/> 预览映射</button>
          <button className="model-btn" onClick={()=>void runValidation()} disabled={busy||!preview||!fingerprint}><CheckCircle2 size={14}/> 运行有界校验</button>
          <button className="model-btn primary" onClick={()=>void saveBinding()} disabled={busy||validation?.status!=='VALID'}><Save size={14}/> 保存 Binding revision</button>
        </div>
        {preview&&<section className="binding-preview"><div className="binding-preview-head"><div><h3>来源样本与映射结果</h3><p>最多读取 20 行；当前源结构指纹 {preview.source.schemaFingerprint.slice(0,16)}…</p></div><span className="binding-chip">{preview.sourceRows.length} 行样本</span></div>
          <div className="binding-preview-grid"><div><h4>来源原始值</h4><pre>{JSON.stringify(preview.sourceRows.slice(0,5),null,2)}</pre></div><div><h4>映射后的对象属性</h4><pre>{JSON.stringify(preview.mappedRows.slice(0,5),null,2)}</pre></div></div></section>}
        {validation&&<section className={'binding-validation '+(validation.status==='VALID'?'pass':'fail')}><strong>{validation.status==='VALID'?'校验通过':'校验未通过'} · {validation.checkedRows} 行</strong><p>校验时间：{new Date(validation.checkedAt).toLocaleString()} · 结构指纹 {validation.schemaFingerprint.slice(0,16)}…</p>
          {validation.issues.length>0&&<ul>{validation.issues.map((i,index)=><li key={i.code+index}>{i.code} · {i.message}（{i.fieldPath}）</li>)}</ul>}</section>}
        <p className="binding-footnote"><ShieldAlert size={14}/> 保存时服务端会重新解析快照、检查来源结构指纹并重新运行校验；客户端显示的 PASS 不构成授权或持久化证据。</p>
      </section>
    </>:detail?<>
      <section className="model-panel">
        <header className="model-panel-head"><div><h2>绑定详情</h2><p>{detail.objectTypeId} · revision {detail.revision}{detail.historical?' · 历史只读版本':''}</p></div>
          <div className="model-actions"><button className="model-btn" onClick={()=>{setDetail(null);setRevisions([]);}}><ArrowLeft size={14}/> 返回目录</button>
            {!detail.historical&&<button className="model-btn primary" onClick={()=>void beginEditor(detail.snapshotId,detail.objectTypeId,detail)}><Plus size={14}/> 创建新 revision</button>}</div></header>
        <div className="binding-contract"><div><span>绑定 ID</span><code>{detail.bindingId}</code></div><div><span>模型快照</span><code>{detail.snapshotId}</code></div>
          <div><span>来源</span><b>{detail.sourceSchema}.{detail.sourceTable}</b></div><div><span>身份域</span><code>{detail.identityDomainId}</code></div>
          <div><span>校验</span><b>{detail.validationStatus} · {detail.checkedRows} 行</b></div><div><span>Revision 摘要</span><code>{detail.digest}</code></div></div>
        <h3 className="binding-section-title">当前版本的属性映射</h3>
        <div className="binding-map-table"><table className="model-table"><thead><tr><th>PropertyRef</th><th>来源列</th><th>权威性</th></tr></thead><tbody>{detail.fieldMappings.map(m=><tr key={m.propertyRef}><td>{m.propertyRef}</td><td>{m.sourceColumn}</td><td>{m.authority}</td></tr>)}</tbody></table></div>
        <h3 className="binding-section-title">不可变版本历史</h3>
        <div className="binding-history">{revisions.map(r=><div key={r.revision} className="binding-history-row"><div><b>revision {r.revision}</b><small>{r.validationStatus} · {r.checkedRows} 行 · {new Date(r.createdAt).toLocaleString()}</small><code>{r.digest}</code></div>
          <button className="model-btn" onClick={()=>void openBinding(detail.bindingId,r.revision)}><FileClock size={14}/> {r.revision===detail.revision?'当前版本':'重开历史版'}</button></div>)}</div>
      </section>
    </>:<>
      <section className="model-panel">
        <header className="model-panel-head"><div><h2>绑定目录</h2><p>按模型包查看每个 ObjectType 的当前 Binding head；历史版本在详情中查看。</p></div><span className="model-count">{bindings.length}</span></header>
        <div className="binding-package-search"><label>模型包 ID<input value={packageId} onChange={e=>setPackageId(e.target.value)} placeholder="例如 manufacturing.production"/></label>
          <button className="model-btn" onClick={()=>void loadBindings()} disabled={busy}>查询绑定</button></div>
        {bindings.length===0?<div className="model-empty compact"><strong>暂无可显示的绑定</strong><span>输入有权访问的模型包 ID，或从 M4 的 Local Snapshot 类型详情点击“创建绑定”。</span></div>
        :<div className="binding-map-table"><table className="model-table"><thead><tr><th>ObjectType</th><th>来源</th><th>当前 revision</th><th>校验</th><th>操作</th></tr></thead><tbody>
          {bindings.map(b=><tr key={b.bindingId}><td><b>{b.objectTypeId}</b><small>{b.packageId}</small></td><td>{b.sourceSchema}.{b.sourceTable}</td><td>revision {b.currentRevision}</td><td><span className={'binding-chip '+(b.validationStatus==='VALID'?'valid':'required')}>{b.validationStatus}</span><small>{b.checkedRows} 行</small></td>
            <td><button className="model-btn" onClick={()=>void openBinding(b.bindingId)}>查看 / 历史</button></td></tr>)}
        </tbody></table></div>}
      </section>
      <section className="model-panel binding-start">
        <header className="model-panel-head"><div><h2>从 Local Snapshot 创建绑定</h2><p>只接受已冻结快照。Validated Draft 需要先冻结，不会被静默转换。</p></div></header>
        <div className="binding-package-search"><label>Local Snapshot ID<input value={snapshotInput} onChange={e=>setSnapshotInput(e.target.value)} placeholder="UUID"/></label>
          <label>ObjectType ID<input value={typeInput} onChange={e=>setTypeInput(e.target.value)} placeholder="例如 supplier"/></label>
          <button className="model-btn primary" disabled={busy||!snapshotInput.trim()||!typeInput.trim()} onClick={()=>void beginEditor(snapshotInput.trim(),typeInput.trim())}><Plus size={14}/> 读取类型并创建绑定</button></div>
      </section>
    </>}
  </div>;
}
