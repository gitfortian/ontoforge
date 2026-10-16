#!/usr/bin/env node
/**
 * OFIP-0004 UX-Low prototype state-contract regression (not Chromium or real API).
 * Run: node doc/prototypes/tests/ofip-0004-lowfi-state-check.mjs
 * Uses only Node standard library and a minimal fake DOM to exercise the mock UI.
 */
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import vm from 'node:vm';

const html=readFileSync(resolve('doc/prototypes/ofip-0004-linktype-lowfi.html'),'utf8');
const match=html.match(/<script>([\s\S]*?)<\/script>/);
if(!match)throw Error('Missing prototype script');
new vm.Script(match[1],{filename:'ofip-0004-linktype-lowfi.html'});

function makeApp(){
  const elements=new Map(),events=new Map();
  function element(id){
    if(!elements.has(id))elements.set(id,{
      textContent:'',innerHTML:'',setAttribute(){},dataset:{}
    });
    return elements.get(id);
  }
  const document={
    getElementById:element,
    addEventListener(type,handler){events.set(type,handler);}
  };
  const window={addEventListener(){}};
  const ctx=vm.createContext({document,window,confirm:()=>true});
  vm.runInContext(match[1],ctx,{filename:'ofip-0004-linktype-lowfi.html'});
  return {
    ctx,
    click(action,index=0){const callback=events.get('click');if(!callback)throw Error('No click handler');callback({target:{closest:()=>({dataset:{action,index:String(index)}})}});},
    state(){return vm.runInContext('S',ctx);},
    eval(script){return vm.runInContext(script,ctx);}
  };
}
let n=0;
function check(name,passed){
  if(!passed)throw Error('FAIL '+name);
  n++;console.log('PASS '+String(n).padStart(2,'0')+' '+name);
}
function ok(app,code){return Boolean(app.eval(code));}

{
  const a=makeApp();
  check('初始一个完整对象、零关系',ok(a,'S.local.objectTypes.length===1 && S.local.linkTypes.length===0'));
  a.click('seed');
  check('制造示例三对象两关系',ok(a,'S.dirty && S.local.objectTypes.length===3 && S.local.linkTypes.length===2'));
  a.eval('validate()');
  check('未保存的编辑不能验证',ok(a,'S.validation.status==="NOT_RUN"'));
  a.eval('save();validate()');
  check('保存后同修订三个对象两关系通过校验',ok(a,'S.rev===2 && S.validation.status==="VALID" && S.validation.rev===2'));
  a.eval('freeze()');
  check('留存的历史完整保留对象和关系',ok(a,'S.snapshots.length===1 && S.snapshots[0].doc.objectTypes.length===3 && S.snapshots[0].doc.linkTypes.length===2'));
  a.eval('S.local.linkTypes[0].cardinality="ONE_TO_ONE";changed();save();validate();freeze()');
  check('第二历史不覆盖第一历史',ok(a,'S.snapshots.length===2 && S.snapshots[0].doc.linkTypes[0].cardinality==="MANY_TO_ONE"'));
  check('关联基数改变产生潜在破坏性差异',ok(a,'diffRows(S.snapshots[0].doc,S.snapshots[1].doc).some(x=>x.path.includes("cardinality") && x.risk==="潜在破坏")'));
  a.eval('S.local.objectTypes.splice(1,1);changed();save();validate()');
  check('删除被引用对象：INVALID 且关系不丢失',ok(a,'S.validation.status==="INVALID" && S.local.linkTypes.length===2 && S.validation.issues.some(i=>i.message.includes("关联对象已不存在"))'));
  a.eval('S.local.objectTypes.push(obj("work-order","WorkOrder","恢复工单","work-order-id","workOrderId"));changed();save();validate()');
  check('恢复对象引用后可以重新校验通过',ok(a,'S.validation.status==="VALID"'));
  a.eval('S.local.linkTypes[0].label="新的显示名";changed()');
  a.click('otherEditor');a.eval('save()');
  check('并发冲突保留全部本地编辑',ok(a,'S.conflict && S.dirty && S.local.linkTypes[0].label==="新的显示名"'));
}
{
  const a=makeApp();a.click('seed');a.click('simulateSaveUnknown'); 
  check('保存回执未知时不谎报已同步',ok(a,'S.pendingSave && S.dirty && S.rev===2'));
  a.click('reconcileSave');
  check('核对内容和修订号后才能确认保存',ok(a,'!S.pendingSave && !S.dirty && S.baseRev===S.rev'));
}
{
  const a=makeApp();a.click('seed');a.eval('save();validate()');a.click('simulateFreezeUnknown');
  check('冻结结果未知时保留唯一历史和原请求',ok(a,'S.pendingFreeze===1 && S.snapshots.length===1'));
  a.click('reconcileFreeze');
  check('原请求找回不能重复留存',ok(a,'S.pendingFreeze===null && S.snapshots.length===1 && S.tab==="history"'));
}
{
  const a=makeApp();a.click('seed');a.eval('save();validate();freeze()');
  a.eval('S.local.linkTypes=[...S.local.linkTypes].reverse();changed();save();validate();freeze()');
  check('仅改变关系排列顺序不产生结构差异',ok(a,'diffRows(S.snapshots[0].doc,S.snapshots[1].doc).length===0'));
}
{
  const a=makeApp();a.click('seed');a.eval('save();validate();freeze()');
  a.eval('S.local.linkTypes[0].evidenceRequired=false;changed();save();validate();freeze()');
  check('放松证据要求必须标记治理复核',ok(a,'diffRows(S.snapshots[0].doc,S.snapshots[1].doc).some(x=>x.path.includes("evidenceRequired") && x.risk==="需治理复核")'));
}
{
  const a=makeApp();a.click('seed');a.eval('save();validate();freeze()');
  a.eval('S.local.linkTypes.splice(0,1);changed();save();validate();freeze()');
  check('删除关系对应潜在破坏性差异',ok(a,'diffRows(S.snapshots[0].doc,S.snapshots[1].doc).some(x=>x.path==="/linkTypes/batch-to-workorder" && x.risk==="潜在破坏")'));
}
console.log('UX-Low mock state checks: '+n+'/'+n+' PASS. No browser, backend or PG was involved.');
