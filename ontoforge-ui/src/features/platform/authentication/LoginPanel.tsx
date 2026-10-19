// Login interaction adapted from Yak Ops apps/web/app/login (85338827); OntoForge API contract.
import { useState } from 'react';
import { LogIn } from 'lucide-react';
import { Button } from '../../../shared/ui/yak/button';
import { Input, PasswordInput } from '../../../shared/ui/yak/input';
import { authenticationMode, beginSignIn, oidcConfigured, passwordSignIn, type WorkspaceIdentity } from '../../../shared/api/auth';
import './login.css';

export default function LoginPanel({onLogin}:{onLogin:(identity:WorkspaceIdentity)=>void}) {
  const [username,setUsername]=useState('');
  const [password,setPassword]=useState('');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const mode=authenticationMode();
  async function login(event:React.FormEvent) {
    event.preventDefault();
    if(busy)return;
    setError('');setBusy(true);
    try {onLogin(await passwordSignIn(username,password));}
    catch(failure){setError(failure instanceof Error?failure.message:'登录失败');}
    finally {setPassword('');setBusy(false);}
  }
  return <div className="login-panel">
    <p>使用你的账号进入本体工作台。</p>
    {mode==='local-session'?<form onSubmit={event=>void login(event)}>
      <label htmlFor="login-username">账号</label>
      <Input id="login-username" name="username" autoComplete="username" value={username}
        onChange={event=>setUsername(event.target.value)} maxLength={64} disabled={busy} required/>
      <label htmlFor="login-password">密码</label>
      <PasswordInput id="login-password" name="password" autoComplete="current-password" value={password}
        showPasswordLabel="显示密码" hidePasswordLabel="隐藏密码"
        onChange={event=>setPassword(event.target.value)} disabled={busy} required/>
      {error&&<p role="alert">{error}</p>}
      <Button type="submit" variant="primary" loading={busy}><LogIn size={16}/>登录</Button>
    </form>:mode==='oidc-jwt'?<>
      {oidcConfigured?<Button variant="primary" onClick={()=>void beginSignIn().catch(failure=>setError(String(failure)))}>使用组织账号登录</Button>:
        <p>尚未配置 OIDC 公共客户端。请按 B0 实施说明配置授权端点、令牌端点和客户端 ID；服务端不会开放匿名模型 API。</p>}
      {error&&<p role="alert">{error}</p>}
    </>:<p>认证配置未就绪，请重新加载页面。</p>}
  </div>;
}
