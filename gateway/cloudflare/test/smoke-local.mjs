import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';

const bootstrap='local-test-bootstrap-secret-with-at-least-32-characters';
const port=8791;
const base='http://127.0.0.1:'+port;
const child=spawn('npx',['wrangler','dev','--local','--port',String(port),'--var','YARA_BOOTSTRAP_TOKEN:'+bootstrap],{stdio:['ignore','pipe','pipe']});
let output='';
child.stdout.on('data',(chunk)=>{output+=chunk.toString();});
child.stderr.on('data',(chunk)=>{output+=chunk.toString();});

async function waitForWorker(){
  for(let i=0;i<40;i+=1){
    if(child.exitCode!==null) throw new Error('wrangler dev exited early: '+output);
    try { const response=await fetch(base+'/healthz',{signal:AbortSignal.timeout(500)}); if(response.ok) return; } catch {}
    await new Promise(resolve=>setTimeout(resolve,250));
  }
  throw new Error('Worker did not become ready: '+output);
}
async function request(path,method='GET',token=null,body=undefined){
  const response=await fetch(base+path,{signal:AbortSignal.timeout(3000),method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{})});
  const text=await response.text(); let data=null; try{data=text?JSON.parse(text):null;}catch{}
  return {status:response.status,data};
}

try {
  await waitForWorker();
  const health=await request('/healthz'); assert.equal(health.status,200); assert.equal(health.data.status,'ok'); process.stdout.write('health ok\\n');
  const first=await request('/v1/admin/agents','POST',bootstrap,{id:'agent-smoke-one',displayName:'Smoke One'}); assert.equal(first.status,201);
  const second=await request('/v1/admin/agents','POST',bootstrap,{id:'agent-smoke-two',displayName:'Smoke Two'}); assert.equal(second.status,201);
  const token1=first.data.token, token2=second.data.token; process.stdout.write('bootstrap ok\\n');
  const identity=await request('/v1/agents/me','GET',token1); assert.equal(identity.status,200); assert.equal(identity.data.id,'agent-smoke-one');
  const created=await request('/v1/tasks','POST',token1,{title:'Smoke task',description:'Exercise task lifecycle.'}); assert.equal(created.status,201);
  const taskId=created.data.task.id; process.stdout.write('task created\\n');
  const claimed=await request('/v1/tasks/claim','POST',token2,{}); assert.equal(claimed.status,200); assert.equal(claimed.data.task.id,taskId);
  const heartbeat=await request('/v1/tasks/'+taskId+'/heartbeat','POST',token2,{}); assert.equal(heartbeat.status,200); process.stdout.write('task claimed and heartbeated\\n');
  const completed=await request('/v1/tasks/'+taskId+'/complete','POST',token2,{outcome:'completed',result:{verified:true}}); assert.equal(completed.status,200); assert.equal(completed.data.task.status,'completed'); assert.deepEqual(completed.data.task.result,{verified:true});
  const sent=await request('/v1/messages','POST',token1,{recipientId:'agent-smoke-two',kind:'message',body:{hello:'world'},idempotencyKey:'smoke-message-1'}); assert.equal(sent.status,201); assert.deepEqual(sent.data.message.body,{hello:'world'});
  const inbox=await request('/v1/messages/inbox','GET',token2); assert.equal(inbox.status,200); assert.ok(inbox.data.messages.some(message=>message.body?.hello==='world'));
  const messageId=sent.data.message.id; process.stdout.write('message send and inbox ok\\n');
  const ack=await request('/v1/messages/'+messageId+'/ack','POST',token2,{}); assert.equal(ack.status,200);
  const github=await request('/v1/projects/iman-shirin/yara-android/branches','POST',token2,{name:'smoke-check'}); assert.equal(github.status,503); assert.equal(github.data.error,'github_app_not_configured');
  const revoked=await request('/v1/admin/agents/agent-smoke-one/revoke','POST',bootstrap,{}); assert.equal(revoked.status,200);
  const denied=await request('/v1/agents/me','GET',token1); assert.equal(denied.status,401);
  process.stdout.write('Worker REST smoke passed: health, bootstrap, identity, task create/claim/heartbeat/complete, message send/inbox/ack, GitHub fail-closed, revoke.\n');
} catch(error) {
  process.stderr.write(String(error)+'\n'+output); process.exitCode=1;
} finally {
  child.kill('SIGTERM');
  await new Promise(resolve=>{ if(child.exitCode!==null) return resolve(); child.once('exit',resolve); setTimeout(resolve,3000); });
}
