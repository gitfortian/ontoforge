import { authorizedFetch } from './auth';

export type Account={accountId:string;principalId:string;username:string;displayName:string;enabled:boolean;systemAdmin:boolean;version:number};
export type AccountPage={items:Account[];total:number;page:number;pageSize:number};
async function request<T>(path:string,method='GET',body?:unknown):Promise<T> {
  const response=await authorizedFetch(path,{method,headers:body?{'Content-Type':'application/json'}:undefined,
    body:body?JSON.stringify(body):undefined});
  if(!response.ok) {
    const failure=await response.json().catch(()=>null) as {message?:string}|null;
    throw new Error(failure?.message??'请求未完成，请重新核对账号列表和版本');
  }
  return response.status===204?undefined as T:await response.json() as T;
}
export const listAccounts=(page:number,q:string)=>request<AccountPage>('/api/platform/accounts?page='+page+'&q='+encodeURIComponent(q));
export const createAccount=(input:{username:string;displayName:string;password:string;systemAdmin:boolean})=>request<Account>('/api/platform/accounts','POST',input);
export const updateAccount=(account:Account,input:{displayName:string;enabled:boolean;systemAdmin:boolean})=>request<Account>('/api/platform/accounts/'+account.accountId,'PUT',{...input,expectedVersion:account.version});
export const resetAccountPassword=(account:Account,password:string)=>request<void>('/api/platform/accounts/'+account.accountId+'/password','POST',{password,expectedVersion:account.version});
export const changeOwnPassword=(currentPassword:string,newPassword:string)=>request<void>('/api/auth/password','POST',{currentPassword,newPassword});
