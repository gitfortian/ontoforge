/**
 * B0 browser authorization-code + PKCE adapter.
 * Tokens live in JS memory ONLY. No localStorage, cookies, anonymous fallback or identity headers.
 * The external IdP must explicitly allow this public SPA client and browser token CORS.
 * /api/me is always the source of truth for principal/workspace/roles.
 */
export type WorkspaceIdentity = {
  principalId:string;issuer:string;subject:string;displayName:string;
  workspaces:Array<{workspaceId:string;workspaceKey:string;displayName:string;roles:string[];packageIds:string[]}>;
};
const KEY='ontoforge:oauth-pkce-pending';
const CLIENT=import.meta.env.VITE_OIDC_CLIENT_ID as string|undefined;
const AUTH_URL=import.meta.env.VITE_OIDC_AUTHORIZATION_ENDPOINT as string|undefined;
const TOKEN_URL=import.meta.env.VITE_OIDC_TOKEN_ENDPOINT as string|undefined;
const SCOPE=(import.meta.env.VITE_OIDC_SCOPE as string|undefined)||'openid';
const REDIRECT=window.location.origin+window.location.pathname;
let accessToken:string|null=null;
let expiresAt=0;
export const oidcConfigured=Boolean(CLIENT&&AUTH_URL&&TOKEN_URL);
function endpoint(raw:string):URL {
  const u=new URL(raw);
  if(u.protocol!=='https:' && !(u.protocol==='http:' && (u.hostname==='127.0.0.1'||u.hostname==='localhost')))
    throw new Error('OIDC 端点必须使用 HTTPS；仅本机测试允许 HTTP loopback');
  if(u.username||u.password||u.hash)throw new Error('OIDC 端点格式不合法');
  return u;
}
function randomBytes(size:number):Uint8Array<ArrayBuffer> {
  const data=new Uint8Array(size);
  crypto.getRandomValues(data);
  return data;
}
function base64Url(data:Uint8Array):string {
  return btoa(Array.from(data,x=>String.fromCharCode(x)).join(''))
    .replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
}
export async function beginSignIn():Promise<void> {
  if(!oidcConfigured||!CLIENT||!AUTH_URL)throw new Error('尚未配置 OIDC 公共客户端');
  const verifier=base64Url(randomBytes(32));
  const state=base64Url(randomBytes(24));
  const challenge=base64Url(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(verifier))));
  sessionStorage.setItem(KEY,JSON.stringify({state,verifier,at:Date.now(),route:window.location.hash||'#/model/packages'}));
  const u=endpoint(AUTH_URL);
  u.searchParams.set('response_type','code');u.searchParams.set('client_id',CLIENT);
  u.searchParams.set('scope',SCOPE);u.searchParams.set('redirect_uri',REDIRECT);
  u.searchParams.set('code_challenge',challenge);u.searchParams.set('code_challenge_method','S256');
  u.searchParams.set('state',state);
  window.location.assign(u.toString());
}
function cleanCallback():void {
  const u=new URL(window.location.href);
  u.searchParams.delete('code');u.searchParams.delete('state');
  u.searchParams.delete('error');u.searchParams.delete('error_description');
  history.replaceState(null,'',u.pathname+u.search+u.hash);
}
function forget():void {
  accessToken=null;expiresAt=0;sessionStorage.removeItem(KEY);
}
export function signOut():void {forget();}
// React StrictMode mounts effects twice in development; exchange each OAuth code ONCE.
let initialization:Promise<WorkspaceIdentity|null>|null=null;
export function initializeIdentity():Promise<WorkspaceIdentity|null> {
  if(initialization)return initialization;
  initialization=initializeIdentityOnce().finally(()=>{initialization=null;});
  return initialization;
}
async function initializeIdentityOnce():Promise<WorkspaceIdentity|null> {
  const u=new URL(window.location.href);
  if(u.searchParams.has('error')){const reason=u.searchParams.get('error')||'login_failed';
    cleanCallback();forget();throw new Error('OIDC 登录未完成：'+reason);}
  if(u.searchParams.has('code')){
    const raw=sessionStorage.getItem(KEY);
    const code=u.searchParams.get('code'),returned=u.searchParams.get('state');
    cleanCallback();sessionStorage.removeItem(KEY);
    let pending:{state:string;verifier:string;at:number;route:string}|null=null;
    try{pending=raw?JSON.parse(raw):null;}catch{/* reject corrupt callback */}
    if(!pending||!code||!returned||pending.state!==returned||Date.now()-pending.at>300000
        ||Date.now()<pending.at-30000||!CLIENT||!TOKEN_URL)
      throw new Error('OIDC 回调状态无效或已过期，请重新登录');
    const response=await fetch(endpoint(TOKEN_URL),{
      method:'POST',cache:'no-store',headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body:new URLSearchParams({grant_type:'authorization_code',client_id:CLIENT,code,
        redirect_uri:REDIRECT,code_verifier:pending.verifier})
    });
    if(!response.ok)throw new Error('OIDC 令牌交换失败（HTTP '+response.status+'）');
    const payload=await response.json() as {access_token?:string;token_type?:string;expires_in?:number};
    if(typeof payload.access_token!=='string'||payload.token_type?.toLowerCase()!=='bearer'
        ||typeof payload.expires_in!=='number'||payload.expires_in<=30)
      throw new Error('OIDC 未返回有效的短期 Bearer 令牌');
    accessToken=payload.access_token;expiresAt=Date.now()+payload.expires_in*1000;
    if(pending.route.startsWith('#/'))window.location.hash=pending.route;
  }
  // Probe trusted /api/me. No cookies or anonymous fallback; in E2E the browser context
  // itself sends a real signed Bearer header via Playwright's network configuration.
  const response=await authorizedFetch('/api/me',{cache:'no-store'});
  if(response.status===401||response.status===403){forget();return null;}
  if(!response.ok)throw new Error('读取服务端可信身份失败（HTTP '+response.status+'）');
  const identity=await response.json() as WorkspaceIdentity;
  if(!identity.principalId||!identity.issuer||!identity.subject||!Array.isArray(identity.workspaces))
    throw new Error('服务端返回的身份信息不完整');
  return identity;
}
export async function authorizedFetch(input:RequestInfo|URL,init:RequestInit={}):Promise<Response> {
  const headers=new Headers(init.headers);
  if(accessToken && Date.now()<expiresAt-15000)headers.set('Authorization','Bearer '+accessToken);
  else {accessToken=null;expiresAt=0;}
  const response=await fetch(input,{...init,headers,credentials:'omit'});
  if(response.status===401 && (String(input).startsWith('/api/model/')||String(input).startsWith('/api/mvp/')||String(input).startsWith('/api/runtime/'))){
    forget();window.dispatchEvent(new Event('ontoforge-auth-expired'));
  }
  return response;
}
