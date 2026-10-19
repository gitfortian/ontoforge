/**
 * Server-selected Session or B0 authorization-code + PKCE adapter.
 * OIDC tokens live in JS memory only; local Session uses HttpOnly cookies and in-memory CSRF.
 * No anonymous fallback or client-selected identity/role headers.
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
export type AuthenticationMode='local-session'|'oidc-jwt'|'mvp-local';
type AuthContext={mode:AuthenticationMode;authenticated:boolean;csrf?:{token:string;headerName:string};capabilities?:{manageAccounts:boolean}};
let context:AuthContext|null=null;
export const authenticationMode=():AuthenticationMode|null=>context?.mode??null;
export const canManageAccounts=():boolean=>context?.capabilities?.manageAccounts===true;
async function loadContext():Promise<AuthContext> {
  const response=await fetch('/api/auth/context',{cache:'no-store',credentials:'same-origin'});
  if(!response.ok)throw new Error('无法读取认证模式');
  const value=await response.json() as AuthContext;
  if(!['local-session','oidc-jwt','mvp-local'].includes(value.mode))throw new Error('服务端认证模式无效');
  if(value.mode==='local-session'&&(!value.csrf?.token||!value.csrf.headerName))throw new Error('无法获取会话安全令牌');
  context=value;
  return value;
}
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
  if(context?.mode!=='oidc-jwt')throw new Error('当前部署不使用 OIDC 登录');
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
  accessToken=null;expiresAt=0;
  try {sessionStorage.removeItem(KEY);}catch {/* local Session does not require browser storage */}
}
export async function signOut():Promise<void> {
  if(context?.mode==='local-session') {
    await loadContext();
    const response=await authorizedFetch('/api/auth/logout',{method:'POST'});
    if(!response.ok)throw new Error('退出会话失败，请重试');
    context=null;
  }
  forget();
}
export async function passwordSignIn(username:string,password:string):Promise<WorkspaceIdentity> {
  await loadContext();
  if(context?.mode!=='local-session')throw new Error('当前部署不支持本地账号登录');
  const response=await authorizedFetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({username,password})});
  if(!response.ok) {
    const failure=await response.json().catch(()=>null) as {message?:string}|null;
    throw new Error(failure?.message??'登录失败，请重试');
  }
  await loadContext(); // login invalidates the anonymous session and rotates CSRF
  return readIdentity();
}
async function readIdentity():Promise<WorkspaceIdentity> {
  const response=await authorizedFetch('/api/me',{cache:'no-store'});
  if(!response.ok)throw new Error('读取服务端可信身份失败（HTTP '+response.status+'）');
  const identity=await response.json() as WorkspaceIdentity;
  if(!identity.principalId||!identity.issuer||!identity.subject||!Array.isArray(identity.workspaces))
    throw new Error('服务端返回的身份信息不完整');
  return identity;
}
// React StrictMode mounts effects twice in development; exchange each OAuth code ONCE.
let initialization:Promise<WorkspaceIdentity|null>|null=null;
export function initializeIdentity():Promise<WorkspaceIdentity|null> {
  if(initialization)return initialization;
  initialization=initializeIdentityOnce().finally(()=>{initialization=null;});
  return initialization;
}
async function initializeIdentityOnce():Promise<WorkspaceIdentity|null> {
  await loadContext();
  const u=new URL(window.location.href);
  if(context?.mode!=='oidc-jwt'&&(u.searchParams.has('code')||u.searchParams.has('error'))){
    cleanCallback();throw new Error('当前认证模式不接受 OIDC 回调');
  }
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
  // Probe trusted /api/me using the server-selected transport. JWT browser fixtures send
  // real signed headers; Session uses only the same-origin HttpOnly cookie.
  const response=await authorizedFetch('/api/me',{cache:'no-store'});
  if(response.status===401||response.status===403){forget();return null;}
  if(!response.ok)throw new Error('读取服务端可信身份失败（HTTP '+response.status+'）');
  const identity=await response.json() as WorkspaceIdentity;
  if(!identity.principalId||!identity.issuer||!identity.subject||!Array.isArray(identity.workspaces))
    throw new Error('服务端返回的身份信息不完整');
  return identity;
}
export async function authorizedFetch(input:RequestInfo|URL,init:RequestInit={}):Promise<Response> {
  const target=new URL(input instanceof Request?input.url:String(input),window.location.href);
  if(target.origin!==window.location.origin||!target.pathname.startsWith('/api/'))throw new Error('认证请求仅允许同源 API');
  const headers=new Headers(init.headers);
  const session=context?.mode==='local-session';
  const method=(init.method||(input instanceof Request?input.method:'GET')).toUpperCase();
  if(session) {
    headers.delete('Authorization');
    if(!['GET','HEAD','OPTIONS'].includes(method)) {
      if(!context?.csrf)throw new Error('会话安全令牌未初始化，请重新登录');
      headers.set(context.csrf.headerName,context.csrf.token);
    }
  }
  else if(accessToken && Date.now()<expiresAt-15000)headers.set('Authorization','Bearer '+accessToken);
  else {accessToken=null;expiresAt=0;}
  const response=await fetch(input,{...init,headers,credentials:session?'same-origin':'omit'});
  if(response.status===401 && ['/api/model/','/api/mvp/','/api/runtime/','/api/platform/'].some(prefix=>target.pathname.startsWith(prefix))){
    forget();window.dispatchEvent(new Event('ontoforge-auth-expired'));
  }
  return response;
}
