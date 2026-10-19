import { Button } from '../../../shared/ui/yak/button';
import React, {useEffect, useState} from 'react';
import {ArrowLeft, BadgeCheck, Database, RefreshCw, ShieldAlert} from 'lucide-react';
import './model.css';
import {authorizedFetch} from '../../../shared/api/auth';

type DraftSourceRef={kind:'VALIDATED_DRAFT';draftId:string;revision:number};
type SnapshotSourceRef={kind:'LOCAL_SNAPSHOT';snapshotId:string};
type SourceRef=DraftSourceRef|SnapshotSourceRef;
type TypeSummary={objectTypeId:string;apiName:string;label?:string;primaryKeyRef:string;propertyCount:number};
type Catalog={sourceRef:SourceRef;packageId:string;modelSourceRevision:number;lifecycle:'VALIDATED_DRAFT_ONLY'|'LOCAL_SNAPSHOT_ONLY';items:TypeSummary[]};
type Property={id:string;apiName:string;label?:string;valueType:string;required:boolean;searchable:boolean;classification:string;enumValues?:string[]};
type Descriptor={sourceRef:SourceRef;packageId:string;modelSourceRevision:number;lifecycle:'VALIDATED_DRAFT_ONLY'|'LOCAL_SNAPSHOT_ONLY';objectTypeId:string;apiName:string;label?:string;primaryKeyRef:string;properties:Property[];payloadSha256?:string};
type SnapshotSummary={snapshotId:string;packageId:string;sourceRevision:number;payloadSha256:string;createdAt:string;lifecycle:'LOCAL_SNAPSHOT_ONLY'};
type SnapshotPage={items:SnapshotSummary[];nextCursor:string|null};
type SnapshotDetail={snapshotId:string;packageId:string;sourceDraftId:string;sourceRevision:number;payloadSha256:string;lifecycle:'LOCAL_SNAPSHOT_ONLY'};
type DraftDetail={draftId:string;packageId:string;revision:number;validation:{status:string;validatedRevision:number|null}};
type ApiError={code?:string;message?:string};
type CurrentDraft={draftId:string;revision:number};
const API='/api/model';
const sourceError=(e:unknown)=>{const x=e as ApiError;return (x.code??'API_ERROR')+' · '+(x.message??'请求失败')};

async function request<T>(path:string,signal?:AbortSignal):Promise<T>{
  let response:Response;
  try{response=await authorizedFetch(path,{cache:'no-store',signal});}
  catch(e){if(signal?.aborted)throw e;throw {code:'NETWORK_UNAVAILABLE',message:'网络不可用，请保留当前工作并重试。'} as ApiError;}
  let body:unknown;
  try{body=await response.json();}catch{throw {code:'INVALID_RESPONSE',message:'服务器返回了无法解析的响应。'} as ApiError;}
  if(!response.ok){const error=body as ApiError;throw {code:error.code??'API_ERROR',message:error.message??'请求失败'} as ApiError;}
  return body as T;
}

export default function RuntimeTypeWorkspace({route,navigate}:{route:string;navigate:(hash:string)=>void}){
  const draftMatch=route.match(/^#\/model\/runtime-types\/drafts\/([0-9a-f-]{36})(?:\?revision=(\d+))?$/i);
  const snapshotMatch=route.match(/^#\/model\/runtime-types\/snapshots\/([0-9a-f-]{36})$/i);
  const draftId=draftMatch?.[1]??null;
  const revision=draftMatch?.[2]?Number(draftMatch[2]):null;
  const snapshotId=snapshotMatch?.[1]??null;
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState<string|null>(null);
  const [detailError,setDetailError]=useState<string|null>(null);
  const [catalog,setCatalog]=useState<Catalog|null>(null);
  const [selectedId,setSelectedId]=useState('');
  const [detail,setDetail]=useState<Descriptor|null>(null);
  const [detailLoading,setDetailLoading]=useState(false);
  const [snapshots,setSnapshots]=useState<SnapshotSummary[]>([]);
  const [currentDraft,setCurrentDraft]=useState<CurrentDraft|null>(null);
  const [refreshKey,setRefreshKey]=useState(0);

  const currentSourceValue=draftId?'draft':snapshotId?'snapshot:'+snapshotId:'invalid';

  useEffect(()=>{
    const controller=new AbortController();
    setLoading(true);setError(null);setDetailError(null);setCatalog(null);setDetail(null);
    setSelectedId('');setSnapshots([]);setCurrentDraft(null);
    if(!draftId&&!snapshotId){
      setError('不支持的运行时类型来源地址。请从已校验草稿或本地快照进入。');
      setLoading(false);
      return ()=>controller.abort();
    }
    if(draftId&&(!Number.isSafeInteger(revision)||revision===null||revision<1)){
      setError('缺少有效 revision。草稿类型引用必须明确指定版本，不能自动选择 latest。');
      setLoading(false);
      return ()=>controller.abort();
    }
    async function load(){
      try{
        const path=draftId
          ?'/api/model/type-sources/drafts/'+draftId+'/types?revision='+revision
          :'/api/model/type-sources/snapshots/'+snapshotId+'/types';
        const result=await request<Catalog>(path,controller.signal);
        if(controller.signal.aborted)return;
        setCatalog(result);
        setSelectedId(result.items[0]?.objectTypeId??'');
        setCurrentDraft(draftId?{draftId,revision:revision!}:null);
        setLoading(false);

        // Snapshot switching is convenience only. Failure to load choices must not hide
        // an otherwise authorized and successfully resolved current source.
        try{
          const page=await request<SnapshotPage>(API+'/packages/'+encodeURIComponent(result.packageId)+'/snapshots?pageSize=50',controller.signal);
          if(!controller.signal.aborted)setSnapshots(page.items);
        }catch{/* Keep the current source usable; server authorization still protects each request. */}

        if(snapshotId){
          try{
            const snapshot=await request<SnapshotDetail>(API+'/snapshots/'+snapshotId,controller.signal);
            const draft=await request<DraftDetail>(API+'/drafts/'+snapshot.sourceDraftId,controller.signal);
            if(!controller.signal.aborted&&draft.validation?.status==='VALID'
              &&draft.validation.validatedRevision===draft.revision){
              setCurrentDraft({draftId:draft.draftId,revision:draft.revision});
            }
          }catch{/* A missing or non-current draft is not a reason to invalidate an intact snapshot. */}
        }
      }catch(e){
        if(!controller.signal.aborted)setError(sourceError(e));
        if(!controller.signal.aborted)setLoading(false);
      }
    }
    void load();
    return ()=>controller.abort();
  },[route,refreshKey]);

  useEffect(()=>{
    const controller=new AbortController();
    setDetail(null);setDetailError(null);
    if(!catalog||!selectedId){setDetailLoading(false);return ()=>controller.abort();}
    setDetailLoading(true);
    const path=draftId
      ?'/api/model/type-sources/drafts/'+draftId+'/types/'+encodeURIComponent(selectedId)+'?revision='+revision
      :'/api/model/type-sources/snapshots/'+snapshotId+'/types/'+encodeURIComponent(selectedId);
    void request<Descriptor>(path,controller.signal).then(value=>{
      if(!controller.signal.aborted)setDetail(value);
    }).catch(e=>{
      if(!controller.signal.aborted)setDetailError(sourceError(e));
    }).finally(()=>{
      if(!controller.signal.aborted)setDetailLoading(false);
    });
    return ()=>controller.abort();
  },[catalog,selectedId,route]);

  function switchSource(value:string){
    if(value==='draft'&&currentDraft){
      navigate('#/model/runtime-types/drafts/'+currentDraft.draftId+'?revision='+currentDraft.revision);
    }else if(value.startsWith('snapshot:')){
      navigate('#/model/runtime-types/snapshots/'+value.slice('snapshot:'.length));
    }
  }
  const returnHash=draftId?'#/model/drafts/'+draftId:snapshotId?'#/model/snapshots/'+snapshotId:'#/model/packages';
  const sourceOptions=(
    <>
      {currentSourceValue!=='draft'&&currentDraft&&<option value="draft">当前已验证草稿 · revision {currentDraft.revision}</option>}
      {snapshots.filter(item=>'snapshot:'+item.snapshotId!==currentSourceValue).map(item=><option key={item.snapshotId} value={'snapshot:'+item.snapshotId}>
        本地快照 · revision {item.sourceRevision} · {item.snapshotId.slice(0,8)}
      </option>)}
    </>
  );

  return <div className="model-page runtime-type-page">
    <div className="model-head">
      <div>
        <div className="model-eyebrow">NATIVE MODEL / M4 RUNTIME TYPE RESOLVER</div>
        <h1>运行时类型目录</h1>
        <p>读取通过校验的 Native ObjectType 定义及其明确来源。这里只展示类型合同，不代表已绑定业务实例、正式发布或可执行 Action。</p>
      </div>
      <div className="model-actions">
        <Button className="model-btn" onClick={()=>setRefreshKey(value=>value+1)} disabled={loading||detailLoading}><RefreshCw size={15}/> 重新核验</Button>
        <Button className="model-btn" onClick={()=>navigate(returnHash)}><ArrowLeft size={15}/> 返回来源</Button>
      </div>
    </div>

    {loading&&<section className="model-panel"><p role="status">正在读取 Native 来源并核验类型合同…</p></section>}
    {error&&<section className="model-panel"><div className="model-alert error" role="alert">{error}</div>
      <p className="model-help">草稿来源需要当前 revision 为 VALID；快照来源需要摘要、规范化字节与 JSONB 镜像核验成功。</p>
      <Button className="model-btn" onClick={()=>setRefreshKey(value=>value+1)}><RefreshCw size={15}/> 重试读取</Button>
    </section>}

    {catalog&& !error&&<>
      <section className="model-panel runtime-source-panel">
        <div className="runtime-source-top">
          <div>
            <div className="runtime-source-title"><Database size={17}/><strong>{catalog.packageId}</strong><span className="model-status valid">{catalog.lifecycle}</span></div>
            <p>来源修订：{catalog.modelSourceRevision} · 类型数量：{catalog.items.length} / 50 · 状态由服务端核验</p>
          </div>
          <div className="runtime-source-switch">
            <label htmlFor="runtime-source">类型来源</label>
            <select id="runtime-source" value={currentSourceValue} onChange={e=>switchSource(e.target.value)}>
              {currentSourceValue==='draft'&&<option value="draft">当前已验证草稿 · revision {catalog.modelSourceRevision}</option>}
              {currentSourceValue.startsWith('snapshot:')&&<option value={currentSourceValue}>当前本地快照 · {snapshotId?.slice(0,8)}</option>}
              {sourceOptions}
            </select>
          </div>
        </div>
        <div className="runtime-trust-note"><BadgeCheck size={16}/><span>{catalog.lifecycle==='VALIDATED_DRAFT_ONLY'
          ?'草稿引用锁定 draftId + revision；草稿保存后此引用将过期。'
          :'本地快照引用锁定 snapshotId，并已由服务端核验 SHA-256 与规范化内容。'}</span></div>
      </section>

      <div className="model-layout runtime-type-layout">
        <section className="model-panel runtime-type-list">
          <header className="model-panel-head"><div><h2>对象类型</h2><p>按稳定 ObjectType ID 排序；选择类型查看属性合同。</p></div><span className="model-count">{catalog.items.length}</span></header>
          {catalog.items.length===0
            ?<div className="model-empty compact"><strong>当前来源没有对象类型</strong><span>回到建模工作台添加对象类型，并保存、校验或冻结新快照。</span></div>
            :catalog.items.map(item=><Button key={item.objectTypeId} className={'runtime-type-item '+(selectedId===item.objectTypeId?'selected':'')}
                onClick={()=>setSelectedId(item.objectTypeId)} aria-pressed={selectedId===item.objectTypeId}>
                <span><b>{item.label||item.apiName}</b><small>{item.apiName} · {item.objectTypeId}</small><small>主键：{item.primaryKeyRef} · 属性 {item.propertyCount}</small></span>
                <span aria-hidden="true">›</span>
              </Button>)}
          <div className="model-footnote"><ShieldAlert size={14}/> 类型定义不等于对象实例或数据绑定。</div>
        </section>

        <section className="model-panel runtime-type-detail">
          <header className="model-panel-head"><div><h2>类型详情</h2><p>只读 Native RuntimeTypeDescriptor</p></div>
            <div className="model-actions">
              {detail&&snapshotId&&<Button className="model-btn primary" onClick={()=>navigate('#/mvp/bindings?packageId='+encodeURIComponent(detail.packageId)+'&snapshotId='+encodeURIComponent(snapshotId)+'&objectTypeId='+encodeURIComponent(detail.objectTypeId))}><Database size={14}/> 创建绑定</Button>}
              {detail&&<span className="model-status valid">{detail.lifecycle}</span>}
            </div></header>
          {detailLoading&&<div className="model-empty compact" role="status">正在读取类型详情…</div>}
          {detailError&&<div className="model-alert error" role="alert">{detailError}</div>}
          {!detailLoading&&!detailError&&!detail&&catalog.items.length>0&&<div className="model-empty compact">请选择左侧对象类型。</div>}
          {detail&&<>
            <div className="runtime-type-title"><h3>{detail.label||detail.apiName}</h3><code>{detail.objectTypeId}</code></div>
            <div className="model-metrics runtime-type-metrics">
              <div><span>API 名称</span><b>{detail.apiName}</b></div>
              <div><span>模型包</span><b>{detail.packageId}</b></div>
              <div><span>来源修订</span><b>{detail.modelSourceRevision}</b></div>
              <div><span>主键属性 ID</span><b>{detail.primaryKeyRef}</b></div>
            </div>
            {detail.payloadSha256&&<div className="snapshot-digest"><label>已核验快照摘要 SHA-256</label><code>{detail.payloadSha256}</code></div>}
            <div className="model-table-wrap"><table className="model-table runtime-property-table">
              <thead><tr><th>属性 / ID</th><th>类型</th><th>必填</th><th>可检索</th><th>分类</th></tr></thead>
              <tbody>{detail.properties.map(property=><tr key={property.id}>
                <td><b>{property.label||property.apiName}</b><small>{property.apiName} · {property.id}</small>
                  {property.enumValues&&<small>枚举：{property.enumValues.join(' / ')}</small>}</td>
                <td>{property.valueType}</td><td>{property.required?'是':'否'}</td><td>{property.searchable?'是':'否'}</td><td>{property.classification}</td>
              </tr>)}</tbody>
            </table></div>
            <p className="model-footnote"><ShieldAlert size={14}/> 当前仅描述模型定义。没有来源事实、对象实例、绑定就绪、正式 Release 或 Action 执行状态。</p>
          </>}
        </section>
      </div>
    </>}
  </div>;
}
