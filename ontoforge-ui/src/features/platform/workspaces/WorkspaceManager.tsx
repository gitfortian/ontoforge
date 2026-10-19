// Yak Ops 85338827 management/workspaces list/form/member-form flows (Apache-2.0).
// OntoForge retains six B0 roles, workspace CAS and explicit globally unique package assignment.
import { useEffect, useRef, useState } from 'react';
import { Button } from '../../../shared/ui/yak/button';
import { Input } from '../../../shared/ui/yak/input';
import { Table, type TableColumns } from '../../../shared/ui/yak/table';
import { Dialog, DialogContent, DialogTitle, DialogDescription } from '../../../shared/ui/yak/dialog';
import {createWorkspace,listWorkspaces,renameWorkspace,workspaceMembers,saveMember,removeMember,registerPackage,type Workspace,type Member,type Members} from '../../../shared/api/workspaces';
import './workspaces.css';

const roles=[['OWNER','空间管理、草稿编辑及已有只读查询'],['EDITOR','草稿编辑及已有只读查询'],['REVIEWER','草稿读取及已有只读查询'],['PUBLISHER','草稿读取及已有只读查询'],['ACTIVATOR','保留角色，当前未开放激活能力'],['VIEWER','保留角色；不授予草稿、快照及依赖其授权的当前查询']];
type Form={kind:'create'}|{kind:'rename'|'package';space:Workspace}|{kind:'member';space:Workspace;member:Member};
export default function WorkspaceManager({onDirtyChange,changed}:{onDirtyChange:(dirty:boolean)=>void;changed:()=>Promise<void>}) {
  const [spaces,setSpaces]=useState<Workspace[]>([]);
  const [detail,setDetail]=useState<Members|null>(null);
  const [query,setQuery]=useState('');
  const [form,setForm]=useState<Form|null>(null);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const dirty=useRef(false);
  // A newer selection/refresh owns its result; a delayed search cannot restore an old roster.
  const generation=useRef(0);
  useEffect(()=>()=>{generation.current++;},[]);
  async function load(id?:string,q='') {
    const current=++generation.current;setBusy(true);setError('');setQuery(q);
    try {
      const list=await listWorkspaces();
      const roster=id&&list.some(space=>space.workspaceId===id)?await workspaceMembers(id,q):null;
      if(current!==generation.current)return;
      setSpaces(list);setDetail(roster);
    }catch(failure){if(current===generation.current){setDetail(null);setSpaces([]);setError(failure instanceof Error?failure.message:'读取空间失败');}}
    finally{if(current===generation.current)setBusy(false);}
  }
  useEffect(()=>{void load();},[]);
  function close(){if(dirty.current&&!window.confirm('工作空间表单尚未保存，是否放弃更改？'))return;setForm(null);dirty.current=false;onDirtyChange(false);}
  async function saved(id?:string){setForm(null);dirty.current=false;onDirtyChange(false);await changed();await load(id);}
  async function remove(member:Member){
    if(!detail||!window.confirm('确认移除 '+member.displayName+'？该主体将立即失去此空间的全部角色。'))return;
    setBusy(true);setError('');
    try{await removeMember(detail.workspace,member.principalId);await changed();await load(detail.workspace.workspaceId);}
    catch(failure){setError(failure instanceof Error?failure.message:'结果未知，请刷新核对');setBusy(false);}
  }
  const columns:TableColumns<Workspace>=[
    {key:'key',title:'空间标识',dataIndex:'workspaceKey'}, {key:'name',title:'名称',dataIndex:'displayName'},
    {key:'roles',title:'我的角色',render:(_,space)=>space.roles.join(', ')||'仅管理权限'},
    {key:'packages',title:'模型包数',render:(_,space)=>space.packageIds.length},
    {key:'open',title:'操作',render:(_,space)=><Button size="small" disabled={busy} aria-label={'查看空间 '+space.workspaceKey} onClick={()=>void load(space.workspaceId)}>查看</Button>},
  ];
  const memberColumns:TableColumns<Member>=[
    {key:'name',title:'主体',render:(_,member)=><span>{member.displayName}{member.username&&<small> · {member.username}</small>}{!member.eligible&&<small> · 当前模式不可用</small>}</span>},
    {key:'roles',title:'角色',render:(_,member)=>member.roles.join(', ')},
    {key:'actions',title:'操作',render:(_,member)=>detail?.workspace.canManage&&<div className="workspace-actions">
      <Button size="small" disabled={busy||!member.eligible} aria-label={'编辑角色 '+(member.username??member.displayName)} onClick={()=>setForm({kind:'member',space:detail.workspace,member})}>编辑角色</Button>
      <Button size="small" disabled={busy} aria-label={'移除成员 '+(member.username??member.displayName)} onClick={()=>void remove(member)}>移除</Button>
    </div>},
  ];
  return <div className="model-page workspace-manager">
    <div className="model-head"><div><h1>工作空间</h1><p>空间组织模型包和成员角色。系统管理员管理空间仍须显式加入，才能获得模型业务权限。</p></div>
      <div className="workspace-actions"><Button disabled={busy} onClick={()=>void load(detail?.workspace.workspaceId)}>刷新空间</Button><Button variant="primary" disabled={busy} onClick={()=>setForm({kind:'create'})}>创建工作空间</Button></div></div>
    {error&&<p role="alert" className="workspace-error">{error}</p>}
    <section className="model-panel"><Table<Workspace> rowKey="workspaceId" columns={columns} dataSource={spaces} loading={busy} pagination={false} scroll={{x:650}}/></section>
    {detail&&<section className="model-panel workspace-detail" aria-label="空间详情">
      <h2>{detail.workspace.displayName}</h2><p><code>{detail.workspace.workspaceKey}</code> · 版本 {detail.workspace.version}</p>
      {detail.workspace.canManage&&<div className="workspace-actions"><Button onClick={()=>setForm({kind:'rename',space:detail.workspace})}>修改空间名称</Button><Button onClick={()=>setForm({kind:'package',space:detail.workspace})}>登记模型包</Button></div>}
      <h3>模型包归属</h3><p>{detail.workspace.packageIds.join('、')||'尚未登记模型包'}</p>
      <h3>成员</h3><Table<Member> rowKey="principalId" columns={memberColumns} dataSource={detail.members} pagination={false} scroll={{x:620}}/>
      {detail.workspace.canManage&&<><h3>添加成员</h3><form className="workspace-search" onSubmit={event=>{event.preventDefault();void load(detail.workspace.workspaceId,query);}}>
        <label htmlFor="workspace-candidate">搜索已登记主体</label><Input id="workspace-candidate" value={query} minLength={2} maxLength={100} required onChange={event=>setQuery(event.target.value)}/><Button type="submit" disabled={busy}>搜索主体</Button>
      </form><p>至少 2 个字符，最多返回 20 个当前认证模式下可用的主体。</p>
      <ul className="workspace-candidates">{detail.candidates.filter(candidate=>!detail.members.some(member=>member.principalId===candidate.principalId)).map(candidate=><li key={candidate.principalId}><span>{candidate.displayName} · {candidate.username}</span><Button size="small" aria-label={'添加成员 '+(candidate.username??candidate.displayName)} onClick={()=>setForm({kind:'member',space:detail.workspace,member:candidate})}>选择角色</Button></li>)}</ul></>}
    </section>}
    <Dialog open={Boolean(form)} onOpenChange={open=>{if(!open)close();}}><DialogContent>
      {form&&<WorkspaceForm key={form.kind+('space'in form?form.space.workspaceId:'')+(form.kind==='member'?form.member.principalId:'')} target={form} close={close} saved={saved} dirty={value=>{dirty.current=value;onDirtyChange(value);}}/>}
    </DialogContent></Dialog>
  </div>;
}

function WorkspaceForm({target,close,saved,dirty}:{target:Form;close:()=>void;saved:(id?:string)=>Promise<void>;dirty:(value:boolean)=>void}) {
  const [key,setKey]=useState('');
  const initialName=target.kind==='rename'?target.space.displayName:'';
  const [name,setName]=useState(initialName);
  const [packageId,setPackageId]=useState('');
  const [selection,setSelection]=useState<string[]>(target.kind==='member'?target.member.roles:[]);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const changed=Boolean(key||packageId)||name!==initialName||selection.join(',')!==(target.kind==='member'?target.member.roles.join(','):'');
  // Report through a ref so parent renders cannot retrigger the effect indefinitely.
  const dirtyCallback=useRef(dirty);dirtyCallback.current=dirty;
  useEffect(()=>{dirtyCallback.current(changed);},[changed]);
  useEffect(()=>()=>dirtyCallback.current(false),[]);
  async function submit(event:React.FormEvent){
    event.preventDefault();if(busy)return;setBusy(true);setError('');
    try{
      let id:string;
      if(target.kind==='create')id=(await createWorkspace(key,name)).workspaceId;
      else {id=target.space.workspaceId;
        if(target.kind==='rename')await renameWorkspace(target.space,name);
        else if(target.kind==='package')await registerPackage(id,packageId);
        else if(target.kind==='member')await saveMember(target.space,target.member.principalId,selection);
      }
      await saved(id);
    }catch(failure){setError((failure instanceof Error?failure.message:'结果未知')+'。请取消表单并刷新核对后再提交。');}
    finally{setBusy(false);}
  }
  return <form className="workspace-form" onSubmit={event=>void submit(event)}>
    <DialogTitle>{target.kind==='create'?'创建工作空间':target.kind==='rename'?'修改空间名称':target.kind==='package'?'登记模型包':'设置成员角色'}</DialogTitle>
    <DialogDescription>{target.kind==='package'?'登记空的新模型包标识后可创建草稿。历史未归属模型包仅系统管理员可分配，已有其他空间归属的模型包不能重复登记。':target.kind==='member'?'角色作用于此空间全部模型包。至少保留一位当前认证模式下可用的 OWNER。成员和名称修改使用空间版本，冲突时须刷新核对。':'空间标识创建后不可更改，创建者成为 OWNER。'}</DialogDescription>
    {target.kind==='create'&&<><label htmlFor="workspace-key">空间标识</label><Input id="workspace-key" value={key} required minLength={3} maxLength={64} pattern="[a-z][a-z0-9-]{2,63}" disabled={busy} onChange={event=>setKey(event.target.value)}/></>}
    {(target.kind==='create'||target.kind==='rename')&&<><label htmlFor="workspace-name">空间名称</label><Input id="workspace-name" value={name} required maxLength={100} disabled={busy} onChange={event=>setName(event.target.value)}/></>}
    {target.kind==='package'&&<><label htmlFor="workspace-package">模型包 ID</label><Input id="workspace-package" value={packageId} required maxLength={160} disabled={busy} onChange={event=>setPackageId(event.target.value)}/></>}
    {target.kind==='member'&&<><p>{target.member.displayName} · {target.member.username}</p><fieldset disabled={busy} className="workspace-roles"><legend>工作空间角色</legend>{roles.map(([role,description])=><label key={role}><input type="checkbox" checked={selection.includes(role)} onChange={event=>setSelection(current=>event.target.checked?[...current,role]:current.filter(value=>value!==role))}/><span><strong>{role}</strong> · {description}</span></label>)}</fieldset></>}
    {error&&<p role="alert" className="workspace-error">{error}</p>}
    <div className="workspace-actions"><Button disabled={busy} onClick={close}>取消</Button><Button type="submit" variant="primary" disabled={target.kind==='member'&&!selection.length} loading={busy}>保存空间设置</Button></div>
  </form>;
}
