// Adapted from Yak Ops 85338827 apps/web/app/management/users index.tsx and form.tsx.
// Retains list/form/reset flows; PostgreSQL CAS, soft disable and admin policy use OntoForge APIs.
import { useEffect, useState } from 'react';
import { Button } from '../../../shared/ui/yak/button';
import { Input, PasswordInput } from '../../../shared/ui/yak/input';
import { Table, type TableColumns } from '../../../shared/ui/yak/table';
import { Dialog, DialogContent, DialogTitle, DialogDescription } from '../../../shared/ui/yak/dialog';
import { createAccount, listAccounts, resetAccountPassword, updateAccount, type Account, type AccountPage } from '../../../shared/api/accounts';
import './accounts.css';

type FormTarget={kind:'create'}|{kind:'edit'|'reset';account:Account};
export default function AccountWorkspace({principalId,onDirtyChange}:{principalId:string;onDirtyChange:(dirty:boolean)=>void}) {
  const [result,setResult]=useState<AccountPage|null>(null);
  const [query,setQuery]=useState('');
  const [appliedQuery,setAppliedQuery]=useState('');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [message,setMessage]=useState('');
  const [form,setForm]=useState<FormTarget|null>(null);
  const [formDirty,setFormDirty]=useState(false);
  async function load(page=1,q=appliedQuery) {
    setBusy(true);setError('');
    try {setResult(await listAccounts(page,q));setAppliedQuery(q);}
    catch(failure){setResult(null);setError(failure instanceof Error?failure.message:'读取账号失败');}
    finally {setBusy(false);}
  }
  useEffect(()=>{void load(1,'');},[]);
  function dirty(value:boolean) {setFormDirty(value);onDirtyChange(value);}
  function close() {
    if(formDirty&&!window.confirm('账号表单尚未保存，是否放弃更改？'))return;
    setForm(null);dirty(false);
  }
  const columns:TableColumns<Account>=[
    {key:'username',title:'账号',dataIndex:'username'},
    {key:'name',title:'显示名称',dataIndex:'displayName'},
    {key:'enabled',title:'状态',render:(_,account)=>account.enabled?'启用':'停用'},
    {key:'admin',title:'账号权限',render:(_,account)=>account.systemAdmin?'系统管理员':'普通用户'},
    {key:'version',title:'版本',dataIndex:'version'},
    {key:'actions',title:'操作',render:(_,account)=><div className="account-row-actions">
      <Button size="small" aria-label={'编辑 '+account.username} onClick={()=>setForm({kind:'edit',account})}>编辑</Button>
      <Button size="small" aria-label={'重置 '+account.username+' 的密码'} onClick={()=>setForm({kind:'reset',account})}>重置密码</Button>
    </div>},
  ];
  return <div className="model-page account-workspace">
    <div className="model-head"><div><h1>账号管理</h1><p>管理本地登录账号。模型包权限仍须在工作空间中显式授予。</p></div>
      {result&&<Button variant="primary" onClick={()=>setForm({kind:'create'})}>新增账号</Button>}</div>
    <section className="model-panel">
      <form className="account-search" onSubmit={event=>{event.preventDefault();void load(1,query);}}>
        <label htmlFor="account-search">搜索账号或名称</label><Input id="account-search" value={query} maxLength={100} onChange={event=>setQuery(event.target.value)}/>
        <Button type="submit" disabled={busy}>查询</Button><Button onClick={()=>void load(result?.page??1)} disabled={busy}>刷新</Button>
      </form>
      {error&&<p className="account-error" role="alert">{error}</p>}{message&&<p role="status">{message}</p>}
      <Table<Account> rowKey="accountId" columns={columns} dataSource={result?.items??[]} loading={busy} pagination={false} scroll={{x:720}}/>
      {result&&<div className="account-pager"><span>共 {result.total} 个账号 · 第 {result.page} 页</span>
        <Button disabled={busy||result.page<=1} onClick={()=>void load(result.page-1)}>上一页</Button>
        <Button disabled={busy||result.page*result.pageSize>=result.total} onClick={()=>void load(result.page+1)}>下一页</Button></div>}
    </section>
    <Dialog open={form!==null} onOpenChange={open=>{if(!open)close();}}>
      <DialogContent>
        {form&&<AccountForm key={form.kind==='create'?'new':form.kind+form.account.accountId} target={form}
          self={form.kind!=='create'&&form.account.principalId===principalId} onDirtyChange={onDirtyChange}
          reportDirty={setFormDirty} close={close} saved={()=>{
            setForm(null);dirty(false);setMessage('账号操作已完成');void load(result?.page??1);
          }}/>}
      </DialogContent>
    </Dialog>
  </div>;
}

function AccountForm({target,self,onDirtyChange,reportDirty,close,saved}:{target:FormTarget;self:boolean;
  onDirtyChange:(dirty:boolean)=>void;reportDirty:(dirty:boolean)=>void;close:()=>void;saved:()=>void}) {
  const account=target.kind==='create'?null:target.account;
  const [username,setUsername]=useState(account?.username??'');
  const [name,setName]=useState(account?.displayName??'');
  const [password,setPassword]=useState('');
  const [enabled,setEnabled]=useState(account?.enabled??true);
  const [admin,setAdmin]=useState(account?.systemAdmin??false);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const changed=Boolean(password)||username!==(account?.username??'')||name!==(account?.displayName??'')||enabled!==(account?.enabled??true)||admin!==(account?.systemAdmin??false);
  useEffect(()=>{onDirtyChange(changed);reportDirty(changed);},[changed,onDirtyChange,reportDirty]);
  useEffect(()=>()=>onDirtyChange(false),[onDirtyChange]);
  async function save(event:React.FormEvent) {
    event.preventDefault();if(busy)return;setBusy(true);setError('');
    try {
      if(target.kind==='create')await createAccount({username,displayName:name,password,systemAdmin:admin});
      else if(target.kind==='reset')await resetAccountPassword(target.account,password);
      else await updateAccount(target.account,{displayName:name,enabled,systemAdmin:admin});
      saved();
    } catch(failure) {setError(failure instanceof Error?failure.message:'操作结果未知，请核对账号列表和版本');}
    finally {setPassword('');setBusy(false);}
  }
  return <form className="account-form" onSubmit={event=>void save(event)}>
    <DialogTitle>{target.kind==='create'?'新增账号':target.kind==='reset'?'重置密码':'编辑账号'}</DialogTitle>
    <DialogDescription>{target.kind==='reset'?'重置后该账号的全部旧会话将失效。':'系统管理员权限不自动授予模型包业务权限。'}</DialogDescription>
    <label htmlFor="account-username">账号</label><Input id="account-username" value={username} onChange={event=>setUsername(event.target.value)} disabled={busy||Boolean(account)} required maxLength={64}/>
    {target.kind!=='reset'&&<><label htmlFor="account-display-name">显示名称</label><Input id="account-display-name" value={name} onChange={event=>setName(event.target.value)} disabled={busy} required maxLength={100}/></>}
    {target.kind!=='edit'&&<><label htmlFor="account-password">{target.kind==='create'?'初始密码':'新密码'}</label>
      <PasswordInput id="account-password" value={password} onChange={event=>setPassword(event.target.value)} disabled={busy} required autoComplete="new-password" showPasswordLabel="显示密码" hidePasswordLabel="隐藏密码"/>
      <small>至少 12 个字符，最多 72 个 UTF-8 字节。</small></>}
    {target.kind!=='reset'&&<>
      {account&&<label className="account-check"><input type="checkbox" checked={enabled} disabled={busy||self} onChange={event=>setEnabled(event.target.checked)}/>启用账号</label>}
      <label className="account-check"><input type="checkbox" checked={admin} disabled={busy||self} onChange={event=>setAdmin(event.target.checked)}/>系统管理员</label>
      {self&&<small>当前管理员不能停用或降级自己。</small>}
    </>}
    {error&&<p role="alert" className="account-error">{error}</p>}
    <div className="account-actions"><Button onClick={close} disabled={busy}>取消</Button><Button type="submit" variant="primary" loading={busy}>保存</Button></div>
  </form>;
}
