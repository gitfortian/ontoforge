import { authorizedFetch } from './auth';

export type Workspace={workspaceId:string;workspaceKey:string;displayName:string;version:number;roles:string[];packageIds:string[];canManage:boolean};
export type Member={principalId:string;displayName:string;username:string|null;eligible:boolean;roles:string[]};
export type Members={workspace:Workspace;members:Member[];candidates:Member[]};
const root='/api/platform/workspaces';
async function request<T>(path:string,method='GET',body?:unknown):Promise<T> {
  const response=await authorizedFetch(root+path,{method,cache:'no-store',headers:body?{'Content-Type':'application/json'}:undefined,body:body?JSON.stringify(body):undefined});
  if(!response.ok){
    const failure=await response.json().catch(()=>null) as {message?:string}|null;
    throw new Error(failure?.message??'请求未完成，请刷新核对空间和版本');
  }
  return response.status===204?undefined as T:await response.json() as T;
}
export const listWorkspaces=()=>request<Workspace[]>('');
export const createWorkspace=(workspaceKey:string,displayName:string)=>request<Workspace>('','POST',{workspaceKey,displayName});
export const renameWorkspace=(space:Workspace,displayName:string)=>request<Workspace>('/'+space.workspaceId,'PUT',{displayName,expectedVersion:space.version});
export const workspaceMembers=(id:string,q='')=>request<Members>('/'+id+'/members?q='+encodeURIComponent(q));
export const saveMember=(space:Workspace,principalId:string,roles:string[])=>request<Members>('/'+space.workspaceId+'/members/'+principalId,'PUT',{roles,expectedVersion:space.version});
export const removeMember=(space:Workspace,principalId:string)=>request<void>('/'+space.workspaceId+'/members/'+principalId+'?expectedVersion='+space.version,'DELETE');
export const registerPackage=(id:string,packageId:string)=>request<Workspace>('/'+id+'/packages','POST',{packageId});
