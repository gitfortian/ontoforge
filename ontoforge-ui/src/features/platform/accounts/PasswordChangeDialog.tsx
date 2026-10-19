import { useState } from 'react';
import { Button } from '../../../shared/ui/yak/button';
import { PasswordInput } from '../../../shared/ui/yak/input';
import { Dialog, DialogContent, DialogTitle, DialogDescription } from '../../../shared/ui/yak/dialog';
import { changeOwnPassword } from '../../../shared/api/accounts';
import './accounts.css';

export default function PasswordChangeDialog({open,close}:{open:boolean;close:()=>void}) {
  const [current,setCurrent]=useState('');const [next,setNext]=useState('');const [confirm,setConfirm]=useState('');
  const [busy,setBusy]=useState(false);const [error,setError]=useState('');
  function dismiss() {
    if(busy)return;
    if((current||next||confirm)&&!window.confirm('放弃尚未提交的密码修改？'))return;
    setCurrent('');setNext('');setConfirm('');setError('');close();
  }
  async function save(event:React.FormEvent) {
    event.preventDefault();if(busy)return;
    if(next!==confirm){setError('两次新密码不一致');return;}
    setBusy(true);setError('');
    try {await changeOwnPassword(current,next);close();window.dispatchEvent(new Event('ontoforge-auth-expired'));}
    catch(failure){setError(failure instanceof Error?failure.message:'修改未完成，请核对会话状态');}
    finally {setCurrent('');setNext('');setConfirm('');setBusy(false);}
  }
  return <Dialog open={open} onOpenChange={value=>{if(!value)dismiss();}}><DialogContent>
    <form className="account-form" onSubmit={event=>void save(event)}>
      <DialogTitle>修改密码</DialogTitle><DialogDescription>修改成功后，当前及其他登录会话全部失效。重新登录后可继续当前未保存的编辑。</DialogDescription>
      <label htmlFor="current-password">当前密码</label><PasswordInput id="current-password" value={current} onChange={event=>setCurrent(event.target.value)} autoComplete="current-password" required disabled={busy} showPasswordLabel="显示当前密码" hidePasswordLabel="隐藏当前密码"/>
      <label htmlFor="new-password">新密码</label><PasswordInput id="new-password" value={next} onChange={event=>setNext(event.target.value)} autoComplete="new-password" required disabled={busy} showPasswordLabel="显示新密码" hidePasswordLabel="隐藏新密码"/>
      <label htmlFor="confirm-password">确认新密码</label><PasswordInput id="confirm-password" value={confirm} onChange={event=>setConfirm(event.target.value)} autoComplete="new-password" required disabled={busy} showPasswordLabel="显示确认密码" hidePasswordLabel="隐藏确认密码"/>
      <small>新密码至少 12 个字符，最多 72 个 UTF-8 字节。</small>
      {error&&<p className="account-error" role="alert">{error}</p>}
      <div className="account-actions"><Button disabled={busy} onClick={dismiss}>取消</Button><Button type="submit" variant="primary" loading={busy}>修改并重新登录</Button></div>
    </form>
  </DialogContent></Dialog>;
}
