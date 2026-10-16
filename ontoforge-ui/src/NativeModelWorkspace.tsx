import React, { useEffect, useRef, useState } from 'react';
import { AlertCircle, ArrowLeft, CheckCircle2, ChevronRight, FilePlus2, Layers3, LoaderCircle, Plus, RefreshCw, Save, ShieldAlert, Trash2 } from 'lucide-react';
import './model.css';
import NativeSnapshotWorkspace, {SnapshotActions} from './NativeSnapshotWorkspace';

type Issue = {path: string; code: string; message: string};
type Report = {status: 'NOT_RUN'|'INCOMPLETE'|'INVALID'|'VALID'; validatedRevision: number|null; issues: Issue[]};
type Property = {id: string; apiName: string; label?: string; valueType: string; required: boolean; searchable: boolean; classification: string; enumValues?: string[]};
type ObjectType = {id: string; apiName: string; label?: string; description?: string; primaryKeyRef: string|null; properties: Property[]};
type LinkType = {id:string;apiName:string;label?:string;sourceTypeRef:string;targetTypeRef:string;cardinality:string;evidenceRequired:boolean};
type EditorDoc = {editorSchemaVersion:'0.1.0';objectTypes:ObjectType[];linkTypes:LinkType[]};
const initialDoc=():EditorDoc=>({editorSchemaVersion:'0.1.0',objectTypes:[],linkTypes:[]});
const newLink=():LinkType=>({id:'',apiName:'',label:'',sourceTypeRef:'',targetTypeRef:'',cardinality:'',evidenceRequired:true});
const cardinalities:ReadonlyArray<readonly [string,string]>=[
  ['ONE_TO_ONE','一对一（每个来源最多关联一个目标，反向亦然）'],
  ['ONE_TO_MANY','一对多（一个来源可关联多个目标）'],
  ['MANY_TO_ONE','多对一（多个来源可关联同一目标）'],
  ['MANY_TO_MANY','多对多（双方均可关联多个）']
];
type Draft = {draftId: string; packageId: string; revision: number; status: 'DRAFT'; editingContent: EditorDoc; validation: Report; createdAt: string; updatedAt: string};
type Summary = {draftId: string; packageId: string; revision: number; validationStatus: string; objectTypeCount: number; updatedAt: string};
type Page = {items: Summary[]; nextCursor: string|null};
type ApiError = {code: string; message: string; issues?: Issue[]};
type Message = {kind: 'success'|'warning'|'error'; text: string};
const initialType = (): ObjectType => ({id: '', apiName: '', label:'', description:'', primaryKeyRef:null, properties:[]});
const newProperty = (): Property => ({id:'',apiName:'',label:'',valueType:'STRING',required:false,searchable:false,classification:'INTERNAL',enumValues:[]});
const statusLabel: Record<string,string> = {NOT_RUN:'未校验',INCOMPLETE:'尚未完成',INVALID:'校验不通过',VALID:'校验通过'};
const apiPrefix='/api/model/drafts';

async function call<T>(path:string, init?:RequestInit):Promise<T> {
  let response:Response;
  try {
    response=await fetch(apiPrefix+path,{cache:'no-store',...init,headers:{'Content-Type':'application/json',...init?.headers}});
  } catch { throw {code:'NETWORK_UNKNOWN',message:'网络不可用或结果未知。请保留本地编辑，恢复后先核对服务器 revision。'} as ApiError; }
  let payload:unknown;
  try {payload=await response.json();} catch {throw {code:'INVALID_RESPONSE',message:'服务器返回了无法解析的响应，请先核对保存状态。'} as ApiError;}
  if(!response.ok) {
    const err=payload as Partial<ApiError>;
    throw {code:err.code??'API_ERROR',message:err.message??'操作失败',issues:err.issues??[]} as ApiError;
  }
  return payload as T;
}
const post=(obj:unknown):RequestInit=>({method:'POST',body:JSON.stringify(obj)});
const put=(obj:unknown):RequestInit=>({method:'PUT',body:JSON.stringify(obj)});

export default function NativeModelWorkspace({onDirtyChange,route,navigate}:{onDirtyChange:(value:boolean)=>void;route:string;navigate:(hash:string)=>void}) {
  const [items,setItems]=useState<Summary[]>([]);
  const [cursor,setCursor]=useState<string|null>(null);
  const [listLoading,setListLoading]=useState(false);
  const [opened,setOpened]=useState<Draft|null>(null);
  const [editorDoc,setEditorDoc]=useState<EditorDoc>(initialDoc);
  const [selectedObject,setSelectedObject]=useState(0);
  const [selectedLink,setSelectedLink]=useState(0);
  const [section,setSection]=useState<'objects'|'links'>('objects');
  const editor=editorDoc.objectTypes[selectedObject]??initialType();
  const activeLink=editorDoc.linkTypes[selectedLink];
  const [dirty,setDirty]=useState(false);
  const [busy,setBusy]=useState(false);
  const [message,setMessage]=useState<Message|null>(null);
  const [packageId,setPackageId]=useState('manufacturing.production');
  const [creating,setCreating]=useState(false);
  const [serverLatest,setServerLatest]=useState<Draft|null>(null);
  const [enumTexts,setEnumTexts]=useState<Record<number,string>>({});
  const [readOnly,setReadOnly]=useState(false);
  const [listPage,setListPage]=useState(1);
  const [validated,setValidated]=useState<Report|null>(null);
  const view=useRef({route,generation:0});
  if(view.current.route!==route) view.current={route,generation:view.current.generation+1};
  const stillCurrent=(generation:number)=>view.current.generation===generation;
  const detailAbort=useRef<AbortController|null>(null);

  useEffect(()=>()=>{
    view.current.generation++;
    detailAbort.current?.abort();
  },[]);

  useEffect(()=>{onDirtyChange(dirty)},[dirty,onDirtyChange]);
  useEffect(()=>{
    const warn=(event:BeforeUnloadEvent)=>{if(dirty){event.preventDefault();event.returnValue='';}};
    window.addEventListener('beforeunload',warn);return ()=>window.removeEventListener('beforeunload',warn);
  },[dirty]);
  const alert=(text:string,kind:Message['kind']='warning')=>setMessage({text,kind});
  const explain=(e:unknown):ApiError=> {
    const x=e as Partial<ApiError>;
    return {code:x.code??'API_ERROR',message:x.message??'操作失败',issues:x.issues??[]};
  };
  async function loadList(nextCursor:string|null=null, page=1) {
    const generation=view.current.generation;
    setListLoading(true);setMessage(null);
    try {
      const result=await call<Page>('?pageSize=20'+(nextCursor?'&cursor='+encodeURIComponent(nextCursor):''));
      if(!stillCurrent(generation))return;
      setItems(result.items);setCursor(result.nextCursor);setListPage(page);
    } catch(e){if(stillCurrent(generation)){const err=explain(e);alert(err.code+' · '+err.message,'error')}}
    finally {if(stillCurrent(generation))setListLoading(false)}
  }
  function applyOpened(d:Draft,discard=false) {
    setOpened(d);
    setReadOnly(false);
    setEditorDoc(structuredClone(d.editingContent));
    setSelectedObject(0);setSelectedLink(0);setSection('objects');
    const data=structuredClone(d.editingContent.objectTypes[0]??initialType());
    setEnumTexts(Object.fromEntries(data.properties.map((p,i)=>[i,(p.enumValues??[]).join(', ')])));
    setValidated(d.validation);
    setServerLatest(null);
    setDirty(false);onDirtyChange(false);if(discard)alert('已按你的确认载入最新服务器版本，原本未提交的本地编辑已放弃。','success');
  }
  async function loadDetail(id:string) {
    const generation=view.current.generation;
    detailAbort.current?.abort();
    const controller=new AbortController();detailAbort.current=controller;
    setBusy(true);setMessage(null);
    try {
      const d=await call<Draft>('/'+id,{signal:controller.signal});
      if(stillCurrent(generation)&&!controller.signal.aborted)applyOpened(d);
    }
    catch(e){if(stillCurrent(generation)&&!controller.signal.aborted){const er=explain(e);alert(er.code+' · '+er.message,'error');setOpened(null);}}
    finally {if(stillCurrent(generation)&&!controller.signal.aborted)setBusy(false)}
  }
  useEffect(()=>{
    detailAbort.current?.abort();
    setOpened(null);setBusy(false);setDirty(false);onDirtyChange(false);setServerLatest(null);setValidated(null);setCreating(false);
    const match=route.match(/^#\/model\/drafts\/([a-f0-9-]{36})$/i);
    if(match) void loadDetail(match[1]);
    else if(route==='#/model/packages') void loadList();
    return ()=>detailAbort.current?.abort();
    // The hash can be opened directly and refreshed without losing the draft ID.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  },[route]);
  const navigateView=(hash:string)=>{
    setMessage(null);
    if(hash===route && hash==='#/model/packages')void loadList();
    else navigate(hash);
  };
  async function createPackage() {
    const generation=view.current.generation;
    setBusy(true);setMessage(null);
    try {
      const d=await call<Draft>('',post({packageId:packageId.trim()}));
      if(!stillCurrent(generation))return;
      setCreating(false);applyOpened(d);window.location.hash='#/model/drafts/'+d.draftId;
      alert('模型包草稿已创建。现在可以添加第一个对象类型。','success');
    } catch(e){if(stillCurrent(generation)){const er=explain(e);alert(er.code+' · '+er.message,'error');}}
    finally{if(stillCurrent(generation))setBusy(false)}
  }
  const markDirty=()=>{
    setDirty(true);onDirtyChange(true);setMessage(null);setServerLatest(null);setValidated(null);
  };
  const mutate=(next:ObjectType)=>{
    setEditorDoc(prev=>({...prev,objectTypes:prev.objectTypes.length
      ?prev.objectTypes.map((t,i)=>i===selectedObject?next:t):[next]}));
    markDirty();
  };
  const updateLink=(next:LinkType)=>{
    setEditorDoc(prev=>({...prev,linkTypes:prev.linkTypes.map((l,i)=>i===selectedLink?next:l)}));
    markDirty();
  };
  const selectObject=(i:number)=>{
    setSelectedObject(i);
    setEnumTexts(Object.fromEntries((editorDoc.objectTypes[i]?.properties??[]).map((p,k)=>[k,(p.enumValues??[]).join(', ')])));
  };
  const addObject=()=>{
    setEditorDoc(prev=>({...prev,objectTypes:[...prev.objectTypes,initialType()]}));
    setSelectedObject(editorDoc.objectTypes.length);setEnumTexts({});
    setSection('objects');markDirty();
  };
  const removeObject=()=>{
    const count=editorDoc.linkTypes.filter(l=>l.sourceTypeRef===editor.id||l.targetTypeRef===editor.id).length;
    if(!window.confirm('删除对象将影响 '+count+' 条关系；关系不会自动删除，需手工修复引用。确认删除对象？'))return;
    setEditorDoc(prev=>({...prev,objectTypes:prev.objectTypes.filter((_,i)=>i!==selectedObject)}));
    setSelectedObject(Math.max(0,selectedObject-1));setEnumTexts({});markDirty();
    alert(count?'已删除对象，'+count+' 条关系保留并需要手动修复。':'对象已从未保存草稿中删除。','warning');
  };
  const addLink=()=>{
    setEditorDoc(prev=>({...prev,linkTypes:[...prev.linkTypes,newLink()]}));
    setSelectedLink(editorDoc.linkTypes.length);setSection('links');markDirty();
  };
  const removeLink=()=>{
    if(!window.confirm('确认从当前草稿删除关系定义？旧历史不会受到影响。'))return;
    setEditorDoc(prev=>({...prev,linkTypes:prev.linkTypes.filter((_,i)=>i!==selectedLink)}));
    setSelectedLink(Math.max(0,selectedLink-1));markDirty();
  };
  const change=(key:keyof ObjectType,value:unknown)=>mutate({...editor,[key]:value});
  const property=(index:number,changed:Partial<Property>)=>{
    const properties=editor.properties.map((p,i)=>i===index?{...p,...changed}:p);
    const current=properties.find(p=>p.id===editor.primaryKeyRef);
    const valid=!!current?.required && (current.valueType==='STRING'||current.valueType==='INTEGER');
    mutate({...editor,properties,primaryKeyRef:valid?editor.primaryKeyRef:null});
  };
  async function save() {
    if(!opened||readOnly||busy)return;
    const generation=view.current.generation;
    setBusy(true);setMessage(null);
    const editingContent:EditorDoc=structuredClone(editorDoc);
    try {
      const next=await call<Draft>('/'+opened.draftId,put({expectedRevision:opened.revision,editingContent}));
      if(!stillCurrent(generation))return;
      applyOpened(next);alert('已写入 PostgreSQL · revision '+next.revision+'。仍是未校验草稿，并非已发布模型。','success');
    } catch(e) {
      if(!stillCurrent(generation))return;
      const er=explain(e);alert(er.code+' · '+er.message,'error');
      if(er.code==='DRAFT_REVISION_CONFLICT'||er.code==='NETWORK_UNKNOWN') {
        try {const latest=await call<Draft>('/'+opened.draftId);if(stillCurrent(generation))setServerLatest(latest)}
        catch {/* Keep local edit untouched; unknown result is not success. */}
      }
    } finally {if(stillCurrent(generation))setBusy(false)}
  }
  async function validate() {
    if(!opened||dirty||readOnly||busy)return;
    const generation=view.current.generation;
    setBusy(true);setMessage(null);
    try {
      const result=await call<Report>('/'+opened.draftId+'/validate',post({expectedRevision:opened.revision}));
      if(!stillCurrent(generation))return;
      setValidated(result);
      alert(result.status==='VALID'?'完整结构与本阶段语义校验通过。仍是草稿，不代表已审核或发布。':
        '校验完成：'+(statusLabel[result.status]??result.status)+'。请根据右侧问题修复，再保存并重新校验。',
        result.status==='VALID'?'success':'warning');
    } catch(e){if(stillCurrent(generation)){const er=explain(e);alert(er.code+' · '+er.message,'error')}}
    finally{if(stillCurrent(generation))setBusy(false)}
  }
  async function inspect() {
    if(!opened)return;setBusy(true);
    const generation=view.current.generation;
    try {
      const last=await call<Draft>('/'+opened.draftId);
      if(!stillCurrent(generation))return;
      setServerLatest(last);
      alert(last.revision===opened.revision?
        '服务器仍为 revision '+last.revision+'。请核对本地编辑后再决定重试。':
        '服务器 revision '+last.revision+' 与本地打开时的 '+opened.revision+' 不同。已保留本地输入。','warning');
    }catch(e){if(stillCurrent(generation)){const er=explain(e);alert(er.code+' · '+er.message,'error')}}
    finally{if(stillCurrent(generation))setBusy(false)}
  }
  const highlight=(path:string)=>{
    const link=path.match(/^\/linkTypes\/(\d+)\/(.+)$/);
    if(link){
      setSelectedLink(Number(link[1]));setSection('links');
      const fields:Record<string,string>={id:'link-id',apiName:'link-api',sourceTypeRef:'link-source',
        targetTypeRef:'link-target',cardinality:'link-cardinality',evidenceRequired:'link-evidence'};
      const id=fields[link[2]];
      window.setTimeout(()=>{const target=id?document.getElementById(id):null;target?.scrollIntoView({behavior:'smooth',block:'center'});target?.focus({preventScroll:true});},30);
      return;
    }
    const object=path.match(/^\/objectTypes\/(\d+)(?:\/properties\/(\d+)\/([^/]+)|\/([^/]+))?$/);
    if(!object)return;
    const i=Number(object[1]);setSelectedObject(i);setSection('objects');
    const data=editorDoc.objectTypes[i];
    setEnumTexts(Object.fromEntries((data?.properties??[]).map((p,k)=>[k,(p.enumValues??[]).join(', ')])));
    const key=object[4]??'';
    const fields:Record<string,string>={id:'obj-id',apiName:'obj-api',primaryKeyRef:'obj-pk',description:'obj-desc'};
    const id=object[2]!==undefined?'prop-'+object[2]+'-'+object[3]:fields[key];
    window.setTimeout(()=>{const target=id?document.getElementById(id):null;target?.scrollIntoView({behavior:'smooth',block:'center'});target?.focus({preventScroll:true});},30);
  };
  const fieldIssues=(path:string)=>!dirty?(validated?.issues??[]).filter(x=>x.path===path):[];
  const issueAttr=(path:string)=>({'aria-invalid':fieldIssues(path).length>0||undefined});
  const renderIssues=(path:string)=><>{fieldIssues(path).map(x=><div key={x.code} className="model-field-error">{x.message}</div>)}</>;
  const validPk=editor.properties.filter(p=>p.id&&p.required&&(p.valueType==='STRING'||p.valueType==='INTEGER'));
  const compare=serverLatest&&(serverLatest.revision!==opened?.revision||dirty);

  // Snapshot history/detail/diff live in the same product workspace and route shell.
  // A direct URL refresh must refetch the persisted PostgreSQL resource.
  if(/^#\/model\/(snapshots\/|packages\/[^/?]+\/snapshots|snapshot-diffs\?)/.test(route))
    return <NativeSnapshotWorkspace route={route} navigate={navigate}/>;
  if(!opened) return <div className="model-page">
    <div className="model-head"><div><div className="model-eyebrow">ONTOLOGY DESIGN / NATIVE MODEL</div><h1>模型包</h1>
      <p>从业务概念出发定义本体。这里展示真实 PostgreSQL 草稿；保存进度、校验与发布是不同操作。</p></div>
      <button className="model-btn model-primary" onClick={()=>setCreating(true)}><FilePlus2 size={16}/> 创建模型包</button></div>
    {message&&<div role="alert" className={'model-alert '+message.kind}>{message.text}</div>}
    {creating&&<section className="model-panel model-create"><h2>创建模型包草稿</h2><p>建议按业务域命名，例如 manufacturing.production</p>
      <label htmlFor="create-package-id">模型包 ID</label><input id="create-package-id" value={packageId} onChange={e=>setPackageId(e.target.value)}/>
      <div className="model-actions"><button className="model-btn model-primary" disabled={busy} onClick={()=>void createPackage()}>创建并继续 <ChevronRight size={16}/></button><button className="model-btn" onClick={()=>setCreating(false)}>取消</button></div></section>}
    <section className="model-panel"><header className="model-panel-head"><div><h2>当前可编辑模型包</h2><p>每个模型包包含多个对象类型和 LinkType；使用同一个保存和校验流程。</p></div>
      <button className="model-btn" onClick={()=>void loadList()} disabled={listLoading}><RefreshCw size={15}/> 刷新</button></header>
      {listLoading?<div className="model-empty"><LoaderCircle size={24}/> 正在读取模型包…</div>:
      items.length===0?<div className="model-empty"><Layers3 size={30}/><strong>还没有模型包草稿</strong><span>创建模型包后，从第一个 ObjectType 开始定义业务概念。</span><button className="model-btn" onClick={()=>setCreating(true)}>新建第一个模型包</button></div>:
      <div className="model-table-wrap"><table className="model-table"><thead><tr><th>模型包</th><th>对象类型</th><th>验证</th><th>Revision</th><th>最后修改</th><th></th></tr></thead><tbody>
        {items.map(x=><tr key={x.draftId}><td><b>{x.packageId}</b><small>草稿 · 未发布</small></td><td>{x.objectTypeCount}</td><td><span className={'model-status '+x.validationStatus.toLowerCase()}>{statusLabel[x.validationStatus]??x.validationStatus}</span></td><td>{x.revision}</td><td>{new Date(x.updatedAt).toLocaleString('zh-CN')}</td><td><button className="model-link" onClick={()=>navigateView('#/model/drafts/'+x.draftId)}>进入建模 <ChevronRight size={14}/></button></td></tr>)}
      </tbody></table></div>}
      {cursor&&<div className="model-pager"><span>第 {listPage} 页</span><button className="model-btn" onClick={()=>void loadList(cursor,listPage+1)}>下一页</button></div>}
    </section>
    <div className="model-footnote"><ShieldAlert size={15}/> 当前仅限本机开发环境。尚未提供认证、授权、模型发布或工业 Action。</div>
  </div>;

  return <div className="model-page">
    <div className="model-head"><div><div className="model-eyebrow"><button className="model-breadcrumb" onClick={()=>navigateView('#/model/packages')}><ArrowLeft size={14}/> 模型包</button> / {opened.packageId}</div>
      <h1>本体建模工作台 <span className="model-status neutral">DRAFT</span></h1><p>先保存编辑进度，再校验当前修订。这个页面使用真实 API，不执行发布或业务动作。</p></div></div>
    {message&&<div role="alert" className={'model-alert '+message.kind}>{message.text}</div>}
    {readOnly&&<div className="model-alert warning">编辑文档包含暂不支持的定义，已限制保存以防资产损坏。</div>}
    {busy&&<div className="model-busy"><LoaderCircle size={15}/> 正在处理请求…</div>}
    <div className="model-steps"><span>01　定义对象</span><span className="selected">02　建立关系</span><span>03　保存并校验</span><span>04　留存本地历史</span></div>
    <div className="model-section-tabs" role="tablist" aria-label="模型包内建模">
      <button role="tab" aria-selected={section==='objects'} className={'model-btn '+(section==='objects'?'model-primary':'')} onClick={()=>setSection('objects')}>对象类型（{editorDoc.objectTypes.length}）</button>
      <button role="tab" aria-selected={section==='links'} className={'model-btn '+(section==='links'?'model-primary':'')} onClick={()=>setSection('links')}>关系类型（{editorDoc.linkTypes.length}）</button>
      <span className="model-help">所有对象和关系属于同一草稿修订；保存只提交完整模型包。</span>
    </div>
    <div className="model-layout">
      <div className="model-main-column">
        {section==='objects'?<>
        <section className="model-panel"><header className="model-panel-head"><div><h2>对象类型（{editorDoc.objectTypes.length}）</h2><p>从对象选择器切换，不影响本地其他对象或关系的编辑进度。</p></div><button className="model-btn" onClick={addObject} disabled={busy}><Plus size={15}/> 新增对象</button></header>
          <div className="model-type-choices">{editorDoc.objectTypes.map((t,i)=><button key={i} className={'model-btn '+(selectedObject===i?'model-primary':'')} onClick={()=>selectObject(i)} disabled={busy}>{t.label||t.apiName||'新对象 '+(i+1)}</button>)}</div>
          {editorDoc.objectTypes.length>0&&<div className="model-actions"><button className="model-btn" onClick={removeObject} disabled={busy}><Trash2 size={14}/> 删除当前对象（保留已有关系）</button><span className="model-help">被 {editorDoc.linkTypes.filter(l=>l.sourceTypeRef===editor.id||l.targetTypeRef===editor.id).length} 条关系引用</span></div>}
        </section>
        <section className="model-panel"><header className="model-panel-head"><div><h2>对象类型定义</h2><p>定义在业务中有独立身份的对象，例如生产批次。</p></div><span className="model-status neutral">{dirty?'● 本地未保存':'草稿已同步'}</span></header>
          <div className="model-form-grid">
            <div><label htmlFor="obj-id">类型 ID</label><input id="obj-id" placeholder="production-batch" value={editor.id} disabled={readOnly||busy} {...issueAttr('/objectTypes/'+selectedObject+'/id')} onChange={e=>change('id',e.target.value)}/>{renderIssues('/objectTypes/'+selectedObject+'/id')}</div>
            <div><label htmlFor="obj-api">API 名称</label><input id="obj-api" placeholder="ProductionBatch" value={editor.apiName} disabled={readOnly||busy} {...issueAttr('/objectTypes/'+selectedObject+'/apiName')} onChange={e=>change('apiName',e.target.value)}/>{renderIssues('/objectTypes/'+selectedObject+'/apiName')}</div>
            <div><label htmlFor="obj-label">显示名称</label><input id="obj-label" placeholder="生产批次" value={editor.label??''} disabled={readOnly||busy} onChange={e=>change('label',e.target.value)}/></div>
            <div><label htmlFor="obj-pk">主键属性</label><select id="obj-pk" value={editor.primaryKeyRef??''} disabled={readOnly||busy} {...issueAttr('/objectTypes/'+selectedObject+'/primaryKeyRef')} onChange={e=>change('primaryKeyRef',e.target.value||null)}><option value="">尚未选择主键</option>{validPk.map((p,i)=><option key={p.id+i} value={p.id}>{p.id} · {p.valueType}</option>)}</select>{renderIssues('/objectTypes/'+selectedObject+'/primaryKeyRef')}<p className="model-help">只允许选择本类型必填的 STRING / INTEGER 属性。</p></div>
            <div className="model-form-wide"><label htmlFor="obj-desc">业务语义定义</label><textarea id="obj-desc" placeholder="解释该对象在制造业务中的含义" rows={3} value={editor.description??''} disabled={readOnly||busy} onChange={e=>change('description',e.target.value)}/></div>
          </div>
        </section>
        <section className="model-panel"><header className="model-panel-head"><div><h2>属性配置 <span className="model-count">{editor.properties.length}</span></h2><p>逐项声明属性、类型、数据分类及必填性。</p></div>
          <button id="add-property" className="model-btn" disabled={readOnly||busy} onClick={()=>mutate({...editor,properties:[...editor.properties,newProperty()]})}><Plus size={15}/> 新增属性</button></header>
          {editor.properties.length===0?<div className="model-empty compact"><strong>还没有定义属性</strong><span>可以先暂存 WIP，再补充字段并设置主键。</span><button className="model-btn" disabled={readOnly||busy} onClick={()=>mutate({...editor,properties:[newProperty()]})}>添加第一个属性</button></div>:editor.properties.map((p,i)=><article className="model-property" key={i}>
            <div className="model-property-head"><strong>Property #{i+1}</strong><button className="model-btn subtle" disabled={readOnly||busy} onClick={()=>{
              const next=editor.properties.filter((_,index)=>index!==i);
              setEnumTexts(Object.fromEntries(next.map((p,index)=>[index,(p.enumValues??[]).join(', ')])));
              mutate({...editor,properties:next,primaryKeyRef:p.id===editor.primaryKeyRef?null:editor.primaryKeyRef});
            }}><Trash2 size={14}/> 删除</button></div>
            <div className="model-prop-grid">
              <div><label htmlFor={'prop-'+i+'-id'}>属性 ID</label><input id={'prop-'+i+'-id'} value={p.id} disabled={readOnly||busy} placeholder="batch-id" {...issueAttr('/objectTypes/'+selectedObject+'/properties/'+i+'/id')} onChange={e=>property(i,{id:e.target.value})}/>{renderIssues('/objectTypes/'+selectedObject+'/properties/'+i+'/id')}</div>
              <div><label htmlFor={'prop-'+i+'-apiName'}>API 名称</label><input id={'prop-'+i+'-apiName'} value={p.apiName} disabled={readOnly||busy} placeholder="batchId" {...issueAttr('/objectTypes/'+selectedObject+'/properties/'+i+'/apiName')} onChange={e=>property(i,{apiName:e.target.value})}/>{renderIssues('/objectTypes/'+selectedObject+'/properties/'+i+'/apiName')}</div>
              <div><label htmlFor={'prop-'+i+'-label'}>显示名称</label><input id={'prop-'+i+'-label'} value={p.label??''} disabled={readOnly||busy} placeholder="批次编号" onChange={e=>property(i,{label:e.target.value})}/></div>
              <div><label htmlFor={'prop-'+i+'-valueType'}>数据类型</label><select id={'prop-'+i+'-valueType'} value={p.valueType} disabled={readOnly||busy} {...issueAttr('/objectTypes/'+selectedObject+'/properties/'+i+'/valueType')} onChange={e=>{setEnumTexts(prev=>({...prev,[i]:''}));property(i,{valueType:e.target.value,enumValues:[]})}}>{['STRING','INTEGER','DECIMAL','BOOLEAN','DATETIME','ENUM'].map(v=><option key={v}>{v}</option>)}</select>{renderIssues('/objectTypes/'+selectedObject+'/properties/'+i+'/valueType')}</div>
              <div><label htmlFor={'prop-'+i+'-classification'}>数据分类</label><select id={'prop-'+i+'-classification'} disabled={readOnly||busy} value={p.classification} onChange={e=>property(i,{classification:e.target.value})}>{['PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED'].map(v=><option key={v}>{v}</option>)}</select></div>
              <div><label>字段配置</label><div className="model-checks"><label htmlFor={'prop-'+i+'-required'}><input id={'prop-'+i+'-required'} type="checkbox" checked={p.required} disabled={readOnly||busy} onChange={e=>property(i,{required:e.target.checked})}/> 必填</label><label htmlFor={'prop-'+i+'-searchable'}><input id={'prop-'+i+'-searchable'} type="checkbox" checked={p.searchable} disabled={readOnly||busy} onChange={e=>property(i,{searchable:e.target.checked})}/> 可搜索</label></div></div>
              {p.valueType==='ENUM'&&<div className="model-form-wide"><label htmlFor={'prop-'+i+'-enumValues'}>ENUM 值（英文逗号分隔）</label><input id={'prop-'+i+'-enumValues'} value={enumTexts[i]??(p.enumValues??[]).join(', ')} disabled={readOnly||busy} {...issueAttr('/objectTypes/'+selectedObject+'/properties/'+i+'/enumValues')} placeholder="PLANNED, RUNNING, COMPLETED" onChange={e=>{
                const raw=e.target.value;
                setEnumTexts(prev=>({...prev,[i]:raw}));
                property(i,{enumValues:raw.split(',').map(x=>x.trim()).filter(Boolean)});
              }}/>{renderIssues('/objectTypes/'+selectedObject+'/properties/'+i+'/enumValues')}</div>}
            </div>
          </article>)}
        </section>
        </>:<section className="model-panel"><header className="model-panel-head"><div><h2>LinkType 关系类型（{editorDoc.linkTypes.length}）</h2><p>定义来源对象 → 目标对象的关系语义；不代表已经存在工单、批次或订单实例。</p></div><button className="model-btn" onClick={addLink} disabled={busy}><Plus size={15}/> 新增关系</button></header>
          <div className="model-type-choices">{editorDoc.linkTypes.map((l,i)=><button key={i} className={'model-btn '+(selectedLink===i?'model-primary':'')} disabled={busy} onClick={()=>setSelectedLink(i)}>{l.label||l.apiName||'新关系 '+(i+1)}</button>)}</div>
          {activeLink?<><div className="model-form-grid">
            <div><label htmlFor="link-id">关系 ID</label><input id="link-id" value={activeLink.id} disabled={busy} onChange={e=>updateLink({...activeLink,id:e.target.value})}/>{renderIssues('/linkTypes/'+selectedLink+'/id')}</div>
            <div><label htmlFor="link-api">API 名称</label><input id="link-api" value={activeLink.apiName} disabled={busy} onChange={e=>updateLink({...activeLink,apiName:e.target.value})}/>{renderIssues('/linkTypes/'+selectedLink+'/apiName')}</div>
            <div className="model-form-wide"><label htmlFor="link-label">业务显示名称</label><input id="link-label" value={activeLink.label??''} disabled={busy} onChange={e=>updateLink({...activeLink,label:e.target.value})}/></div>
            <div><label htmlFor="link-source">来源对象类型</label><select id="link-source" value={activeLink.sourceTypeRef} disabled={busy} onChange={e=>updateLink({...activeLink,sourceTypeRef:e.target.value})}><option value="">请选择来源对象</option>{activeLink.sourceTypeRef&&!editorDoc.objectTypes.some(t=>t.id===activeLink.sourceTypeRef)&&<option value={activeLink.sourceTypeRef}>⚠ 已不存在：{activeLink.sourceTypeRef}（请修复）</option>}{editorDoc.objectTypes.map((t,i)=><option key={i} value={t.id}>{t.label||t.apiName||t.id}</option>)}</select>{renderIssues('/linkTypes/'+selectedLink+'/sourceTypeRef')}</div>
            <div><label htmlFor="link-target">目标对象类型</label><select id="link-target" value={activeLink.targetTypeRef} disabled={busy} onChange={e=>updateLink({...activeLink,targetTypeRef:e.target.value})}><option value="">请选择目标对象</option>{activeLink.targetTypeRef&&!editorDoc.objectTypes.some(t=>t.id===activeLink.targetTypeRef)&&<option value={activeLink.targetTypeRef}>⚠ 已不存在：{activeLink.targetTypeRef}（请修复）</option>}{editorDoc.objectTypes.map((t,i)=><option key={i} value={t.id}>{t.label||t.apiName||t.id}</option>)}</select>{renderIssues('/linkTypes/'+selectedLink+'/targetTypeRef')}</div>
            <div><label htmlFor="link-cardinality">关联基数（来源 → 目标）</label><select id="link-cardinality" value={activeLink.cardinality} disabled={busy} onChange={e=>updateLink({...activeLink,cardinality:e.target.value})}><option value="">请选择关联基数</option>{cardinalities.map(([code,label])=><option key={code} value={code}>{label}</option>)}</select>{renderIssues('/linkTypes/'+selectedLink+'/cardinality')}</div>
            <div><label htmlFor="link-evidence">未来关联是否必须有来源证据</label><select id="link-evidence" value={String(activeLink.evidenceRequired)} disabled={busy} onChange={e=>updateLink({...activeLink,evidenceRequired:e.target.value==='true'})}><option value="true">需要证据</option><option value="false">不强制来源证据</option></select>{renderIssues('/linkTypes/'+selectedLink+'/evidenceRequired')}</div>
          </div>{[activeLink.sourceTypeRef,activeLink.targetTypeRef].some(ref=>ref&&!editorDoc.objectTypes.some(t=>t.id===ref))&&
            <div className="model-alert warning" role="alert">此关系仍引用已删除或改名的对象，原始引用未被自动改写。可以保存修订进度，但不能通过完整校验或冻结历史；请选择正确的对象修复。</div>}
            <div className="model-info">仅定义关系类型。关联基数不表示已存在关系记录；尚无 Data Binding、LinkFact 或查询能力。</div>
            <div className="model-actions"><button className="model-btn" onClick={removeLink} disabled={busy}><Trash2 size={14}/> 删除当前关系</button></div></>:<div className="model-empty compact">暂无关系类型；可先保存零关系的合法模型包。</div>}
        </section>}
      </div>
      <div className="model-aside-column">
        <section className="model-panel"><header className="model-panel-head"><div><h2>草稿与校验</h2><p>状态来自 PostgreSQL，校验绑定修订号。</p></div></header>
          <div className="model-metrics"><div><span>服务器 revision</span><b>{opened.revision}</b></div><div><span>当前编辑</span><b>{dirty?'未保存':'已同步'}</b></div><div><span>校验结果</span><strong className={'model-status '+(!dirty?(validated?.status??'NOT_RUN'):'NOT_RUN').toLowerCase()}>{dirty?'编辑待保存':statusLabel[validated?.status??'NOT_RUN']}</strong></div><div><span>受检 revision</span><b>{dirty?'—':(validated?.validatedRevision??'—')}</b></div></div>
          <div className="model-info"><CheckCircle2 size={16}/> 先完成保存，再校验确切的 revision。校验通过不等于审核、发布或激活。</div>
          <button className="model-btn model-primary model-full" disabled={busy||dirty||readOnly} onClick={()=>void validate()}><CheckCircle2 size={16}/> 检查模型完整性</button>
          <div className="model-issues">{dirty?<p>当前有未保存修改，旧校验结果不适用于本地表单。</p>:(validated?.issues?.length??0)>0?<><b>发现 {validated!.issues.length} 个问题</b>{validated!.issues.map((x,i)=><button className="model-issue-link" key={i} onClick={()=>highlight(x.path)}><span>{x.message}</span><small>{x.path}</small></button>)}</>:<p>{validated?.status==='VALID'?'当前修订已通过本阶段模型校验。':'尚无待修复问题；未校验不等于通过。'}</p>}</div>
        </section>
        <SnapshotActions key={opened.draftId} draft={opened} validation={validated}
                         dirty={dirty} locked={readOnly||busy} navigate={navigate}/>
        <section className="model-panel"><header className="model-panel-head"><div><h2>异常恢复</h2><p>当保存结果未知或发生并发更新时</p></div></header>
          <button className="model-btn model-full" disabled={busy} onClick={()=>void inspect()}><RefreshCw size={15}/> 核对服务器最新版</button>
          {compare&&<div className="model-conflict"><div className="model-alert warning"><AlertCircle size={15}/> 注意：本地与服务器版本可能不同。下面仅供对照，不会自动覆盖。</div><div><b>本地 · 基于 revision {opened.revision}</b><pre>{JSON.stringify(editorDoc,null,2)}</pre></div><div><b>服务器 · revision {serverLatest.revision}</b><pre>{JSON.stringify(serverLatest.editingContent,null,2)}</pre></div>
            <button className="model-btn model-full" onClick={()=>{
              if(!window.confirm('确认放弃当前所有未保存编辑，并载入服务器最新版？此操作不可撤销。'))return;
              applyOpened(serverLatest,true);
            }}>确认放弃本地更改并载入最新版</button></div>}
        </section>
      </div>
    </div>
    <div className="model-sticky"><span>{dirty?'● 有未保存的本地编辑':'✓ 当前编辑已与服务器同步'}　 ·　草稿 revision {opened.revision}</span><div><button className="model-btn" onClick={()=>navigate('#/model/packages')}><ArrowLeft size={15}/> 返回模型包</button><button className="model-btn model-primary" disabled={busy||readOnly||!dirty} onClick={()=>void save()}><Save size={16}/> 保存编辑进度</button></div></div>
  </div>;
}
