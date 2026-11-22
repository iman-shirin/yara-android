import { createMcpHandler, McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { assertAgentBranch, assertWritablePath, hashToken, issueAgentToken, safeEqual, validAgentId, validRepoPart, validRepoPath, parseBearer, decodeBase64, encodeBase64, base64Url } from './security.mjs';
import { validateTaskPayload, validateMessagePayload, byteLength } from './collaboration.mjs';

const json = (value, status = 200, headers = {}) => new Response(JSON.stringify(value), { status, headers: { 'content-type':'application/json; charset=utf-8', 'cache-control':'no-store', ...headers } });
const err = (code, status = 400) => json({ error:code }, status);
const nowIso = () => new Date().toISOString();
const uuid = () => crypto.randomUUID();
const parseJson = async (request) => { try { return await request.json(); } catch { throw Object.assign(new Error('invalid_json'), { status:400 }); } };
const asJson = (value) => JSON.stringify(value ?? {});
const getRow = async (stmt) => stmt.first();
const normalizeTask = (row) => row ? { ...row, result: typeof row.result === 'string' ? JSON.parse(row.result) : row.result } : row;
const normalizeMessage = (row) => row ? { ...row, body: typeof row.body === 'string' ? JSON.parse(row.body) : row.body } : row;

async function audit(env, agentId, action, resource, outcome, requestId, details = {}) {
  await env.DB.prepare('INSERT INTO yara_audit(agent_id,action,resource,outcome,request_id,details) VALUES(?,?,?,?,?,?)')
    .bind(agentId ?? null, action, resource, outcome, requestId ?? null, asJson(details)).run();
}

async function authenticate(request, env) {
  const token = parseBearer(request.headers.get('authorization'));
  if (!token) return null;
  const tokenHash = await hashToken(token);
  return getRow(env.DB.prepare('SELECT id,display_name FROM yara_agents WHERE token_hash=? AND active=1').bind(tokenHash));
}

function derLength(length) { if (length < 128) return Uint8Array.of(length); const bytes=[]; let n=length; while(n>0){bytes.unshift(n&255);n>>=8;} return Uint8Array.of(0x80|bytes.length,...bytes); }
function der(tag, value) { const len=derLength(value.length); const out=new Uint8Array(1+len.length+value.length); out[0]=tag; out.set(len,1); out.set(value,1+len.length); return out; }
function concatBytes(...arrays) { const out=new Uint8Array(arrays.reduce((n,a)=>n+a.length,0)); let offset=0; for(const a of arrays){out.set(a,offset);offset+=a.length;} return out; }
function rsaPkcs1ToPkcs8(pkcs1) { const version=Uint8Array.of(0x02,0x01,0x00); const algorithm=Uint8Array.of(0x30,0x0d,0x06,0x09,0x2a,0x86,0x48,0x86,0xf7,0x0d,0x01,0x01,0x01,0x05,0x00); return der(0x30,concatBytes(version,algorithm,der(0x04,pkcs1))); }

let cachedInstallationToken = null;
let cachedInstallationTokenExpiresAt = 0;
async function githubToken(env) {
  const keys = ['GITHUB_APP_ID','GITHUB_INSTALLATION_ID','GITHUB_APP_PRIVATE_KEY_BASE64'];
  const present = keys.filter((key) => Boolean(env[key]));
  if (present.length === 0 || present.length !== keys.length) throw Object.assign(new Error('github_app_not_configured'), { status:503 });
  if (cachedInstallationToken && Date.now() < cachedInstallationTokenExpiresAt - 60000) return cachedInstallationToken;
  const pem = new TextDecoder().decode(decodeBase64(env.GITHUB_APP_PRIVATE_KEY_BASE64)).replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, '');
  const der = decodeBase64(pem);
  const key = await crypto.subtle.importKey('pkcs8', der, { name:'RSASSA-PKCS1-v1_5', hash:'SHA-256' }, false, ['sign']);
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(new TextEncoder().encode(JSON.stringify({ alg:'RS256', typ:'JWT' })));
  const payload = base64Url(new TextEncoder().encode(JSON.stringify({ iat:now-30, exp:now+540, iss:env.GITHUB_APP_ID })));
  const unsigned = header + '.' + payload;
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned));
  const jwt = unsigned + '.' + base64Url(new Uint8Array(signature));
  const response = await fetch('https://api.github.com/app/installations/' + encodeURIComponent(env.GITHUB_INSTALLATION_ID) + '/access_tokens', {
    method:'POST', headers:{ Authorization:'Bearer '+jwt, Accept:'application/vnd.github+json', 'X-GitHub-Api-Version':'2022-11-28' }
  });
  if (!response.ok) throw Object.assign(new Error('github_app_token_failed'), { status:502 });
  const data = await response.json();
  cachedInstallationToken = data.token;
  cachedInstallationTokenExpiresAt = Date.parse(data.expires_at);
  return cachedInstallationToken;
}

async function githubRequest(env, path, options = {}) {
  const token = await githubToken(env);
  const response = await fetch('https://api.github.com' + path, { ...options, headers:{
    Authorization:'Bearer '+token, Accept:'application/vnd.github+json', 'X-GitHub-Api-Version':'2022-11-28',
    ...(options.body ? { 'Content-Type':'application/json' } : {}), ...(options.headers ?? {})
  }});
  const body = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) throw Object.assign(new Error('github_api_error'), { status:response.status === 404 ? 404 : response.status === 409 ? 409 : 502 });
  return body;
}

function validateRepo(env, owner, repo, path) {
  if (!validRepoPart(owner) || !validRepoPart(repo) || !validRepoPath(path)) throw Object.assign(new Error('invalid_resource_path'), { status:400 });
  if (owner + '/' + repo !== env.GITHUB_REPOSITORY) throw Object.assign(new Error('repository_not_allowed'), { status:403 });
}

async function callTool(env, agent, name, args, requestId) {
  const db = env.DB;
  if (name === 'yara_identity') { await audit(env,agent.id,'mcp.identity',agent.id,'success',requestId); return { id:agent.id, displayName:agent.display_name, status:'active' }; }
  if (name === 'yara_task_create') {
    const parsed = validateTaskPayload(args); if (!parsed.ok) throw new Error(parsed.error);
    const v = parsed.value;
    if (v.assignedTo && !(await getRow(db.prepare('SELECT id FROM yara_agents WHERE id=? AND active=1').bind(v.assignedTo)))) throw new Error('assignee_not_found');
    const id = uuid();
    await db.prepare('INSERT INTO yara_tasks(id,title,description,created_by,assigned_to,max_attempts) VALUES(?,?,?,?,?,?)').bind(id,v.title,v.description,agent.id,v.assignedTo,v.maxAttempts).run();
    await audit(env,agent.id,'task.create',id,'success',requestId,{ assignedTo:v.assignedTo });
    return { task:normalizeTask(await getRow(db.prepare('SELECT * FROM yara_tasks WHERE id=?').bind(id))) };
  }
  if (name === 'yara_tasks_list') {
    const state = args.status; if (state && !['queued','running','completed','failed','cancelled'].includes(state)) throw new Error('invalid_status');
    const sql = 'SELECT id,title,description,created_by,assigned_to,status,attempts,max_attempts,lease_until,result,created_at,updated_at,completed_at FROM yara_tasks WHERE (created_by=? OR assigned_to=? OR (assigned_to IS NULL AND status=\'queued\'))' + (state ? ' AND status=?' : '') + ' ORDER BY created_at DESC LIMIT 100';
    const query = state ? db.prepare(sql).bind(agent.id,agent.id,state) : db.prepare(sql).bind(agent.id,agent.id);
    return { tasks:(await query.all()).results.map(normalizeTask) };
  }
  if (name === 'yara_task_claim') {
    const stamp = nowIso(), lease = new Date(Date.now()+180000).toISOString();
    const batch = await db.batch([
      db.prepare("UPDATE yara_tasks SET status='failed',lease_until=NULL,completed_at=?,updated_at=? WHERE status='running' AND lease_until < ? AND attempts >= max_attempts").bind(stamp,stamp,stamp),
      db.prepare("UPDATE yara_tasks SET status='queued',assigned_to=NULL,lease_until=NULL,updated_at=? WHERE status='running' AND lease_until < ? AND attempts < max_attempts").bind(stamp,stamp),
      db.prepare("UPDATE yara_tasks SET status='running',assigned_to=?,attempts=attempts+1,lease_until=?,updated_at=? WHERE id=(SELECT id FROM yara_tasks WHERE status='queued' AND (assigned_to IS NULL OR assigned_to=?) AND attempts < max_attempts ORDER BY created_at LIMIT 1) AND status='queued' RETURNING *").bind(agent.id,lease,stamp,agent.id)
    ]);
    const task = batch[2].results?.[0] ?? null;
    if (task) await audit(env,agent.id,'task.claim',task.id,'success',requestId,{ attempt:task.attempts });
    return { task:normalizeTask(task) };
  }
  if (name === 'yara_task_heartbeat') {
    const lease = new Date(Date.now()+180000).toISOString();
    const result = await db.prepare("UPDATE yara_tasks SET lease_until=?,updated_at=? WHERE id=? AND assigned_to=? AND status='running' RETURNING id,lease_until").bind(lease,nowIso(),args.taskId,agent.id).first();
    if (!result) throw new Error('active_task_not_found'); return result;
  }
  if (name === 'yara_task_complete') {
    const outcome = args.outcome ?? 'completed'; if (!['completed','failed'].includes(outcome)) throw new Error('invalid_outcome');
    const resultBody = args.result ?? {}; const serialized = asJson(resultBody); if (byteLength(serialized)>50000) throw new Error('result_too_large');
    const current = await getRow(db.prepare("SELECT * FROM yara_tasks WHERE id=? AND assigned_to=? AND status='running'").bind(args.taskId,agent.id));
    if (!current) throw new Error('active_task_not_found');
    const retry = outcome === 'failed' && current.attempts < current.max_attempts;
    const status = outcome === 'completed' ? 'completed' : retry ? 'queued' : 'failed';
    const stamp = nowIso(), messageId = uuid();
    const batch = await db.batch([
      db.prepare("UPDATE yara_tasks SET status=?,assigned_to=CASE WHEN ?='queued' THEN NULL ELSE assigned_to END,result=?,lease_until=NULL,updated_at=?,completed_at=CASE WHEN ? IN ('completed','failed') THEN ? ELSE NULL END WHERE id=? AND assigned_to=? AND status='running' RETURNING id").bind(status,status,serialized,stamp,status,stamp,args.taskId,agent.id),
      db.prepare("INSERT INTO yara_messages(id,task_id,sender_id,recipient_id,kind,body) SELECT ?,?,?,?,'result',? WHERE EXISTS (SELECT 1 FROM yara_tasks WHERE id=? AND status=? AND updated_at=?)").bind(messageId,args.taskId,agent.id,current.created_by,asJson({ outcome,retryScheduled:retry,result:resultBody }),args.taskId,status,stamp)
    ]);
    if (!batch[0].results?.length) throw new Error('active_task_not_found');
    await audit(env,agent.id,'task.complete',args.taskId,'success',requestId,{ status });
    return { task:normalizeTask(await getRow(db.prepare('SELECT * FROM yara_tasks WHERE id=?').bind(args.taskId))), retryScheduled:retry };
  }
  if (name === 'yara_message_send') {
    const parsed = validateMessagePayload(args); if (!parsed.ok) throw new Error(parsed.error); const v=parsed.value;
    if (v.recipientId && !(await getRow(db.prepare('SELECT id FROM yara_agents WHERE id=? AND active=1').bind(v.recipientId)))) throw new Error('recipient_not_found');
    if (v.taskId) {
      const task=await getRow(db.prepare('SELECT created_by,assigned_to FROM yara_tasks WHERE id=?').bind(v.taskId));
      const participants=task?[task.created_by,task.assigned_to].filter(Boolean):[];
      if (!participants.includes(agent.id) || (v.recipientId && !participants.includes(v.recipientId))) throw new Error('task_not_found');
    }
    const id=uuid();
    const inserted=await db.prepare('INSERT INTO yara_messages(id,task_id,sender_id,recipient_id,kind,body,idempotency_key) VALUES(?,?,?,?,?,?,?) ON CONFLICT(sender_id,idempotency_key) DO NOTHING RETURNING *').bind(id,v.taskId,agent.id,v.recipientId,v.kind,asJson(v.body),v.idempotencyKey).first();
    if (!inserted) throw new Error('duplicate_message');
    await audit(env,agent.id,'message.send',v.recipientId ?? 'broadcast','success',requestId,{ messageId:id }); return { message:normalizeMessage(inserted) };
  }
  if (name === 'yara_message_inbox') {
    const since=args.since ?? null;
    const rows=await db.prepare('SELECT id,task_id,sender_id,recipient_id,kind,body,created_at,read_at FROM yara_messages WHERE (recipient_id=? OR recipient_id IS NULL) AND (? IS NULL OR created_at>?) ORDER BY created_at ASC LIMIT 100').bind(agent.id,since,since).all();
    return { messages:rows.results.map(normalizeMessage) };
  }
  if (name === 'yara_message_ack') {
    const row=await db.prepare('UPDATE yara_messages SET read_at=? WHERE id=? AND (recipient_id=? OR recipient_id IS NULL) RETURNING id,read_at').bind(nowIso(),args.messageId,agent.id).first();
    if (!row) throw new Error('message_not_found'); await audit(env,agent.id,'message.ack',args.messageId,'success',requestId); return row;
  }
  const owner=args.owner, repo=args.repo;
  if (!validRepoPart(owner) || !validRepoPart(repo) || owner+'/'+repo!==env.GITHUB_REPOSITORY) throw new Error('repository_not_allowed');
  if (name==='yara_read_file') {
    validateRepo(env,owner,repo,args.path); const data=await githubRequest(env,'/repos/'+owner+'/'+repo+'/contents/'+args.path.split('/').map(encodeURIComponent).join('/'));
    if (Array.isArray(data)||!data.content) throw new Error('file_not_found');
    const bytes=Uint8Array.from(atob(data.content.replace(/\n/g,'')),c=>c.charCodeAt(0));
    await audit(env,agent.id,'mcp.file.read',owner+'/'+repo+'/'+args.path,'success',requestId,{sha:data.sha});
    return {path:args.path,sha:data.sha,content:new TextDecoder().decode(bytes)};
  }
  if (name==='yara_create_branch') {
    if (typeof args.name!=='string'||!/^[a-z0-9][a-z0-9-]{1,79}$/.test(args.name)) throw new Error('invalid_branch_name');
    const branch='agents/'+agent.id+'/'+args.name; assertAgentBranch(agent.id,branch);
    const base=await githubRequest(env,'/repos/'+owner+'/'+repo+'/git/ref/heads/main');
    const created=await githubRequest(env,'/repos/'+owner+'/'+repo+'/git/refs',{method:'POST',body:JSON.stringify({ref:'refs/heads/'+branch,sha:base.object.sha})});
    await audit(env,agent.id,'mcp.branch.create',owner+'/'+repo+'/'+branch,'success',requestId,{baseSha:base.object.sha}); return {branch,base:'main',commitSha:created.object.sha};
  }
  if (name==='yara_write_file') {
    validateRepo(env,owner,repo,args.path); assertAgentBranch(agent.id,args.branch); assertWritablePath(args.path);
    if (typeof args.content!=='string'||byteLength(args.content)>500000||typeof args.message!=='string'||!args.message.trim()||args.message.length>200) throw new Error('invalid_write_payload');
    const payload={message:args.message,content:encodeBase64(new TextEncoder().encode(args.content)),branch:args.branch};
    if (typeof args.sha==='string'&&/^[a-f0-9]{40}$/.test(args.sha)) payload.sha=args.sha;
    const data=await githubRequest(env,'/repos/'+owner+'/'+repo+'/contents/'+args.path.split('/').map(encodeURIComponent).join('/'),{method:'PUT',body:JSON.stringify(payload)});
    await audit(env,agent.id,'mcp.file.write',owner+'/'+repo+'/'+args.path,'success',requestId,{branch:args.branch,commitSha:data.commit?.sha}); return {branch:args.branch,commitSha:data.commit?.sha,contentSha:data.content?.sha};
  }
  if (name==='yara_create_draft_pr') {
    if (typeof args.title!=='string'||!args.title.trim()||args.title.length>200||typeof(args.body??'')!=='string'||(args.body??'').length>10000) throw new Error('invalid_pull_request_payload');
    assertAgentBranch(agent.id,args.head);
    const data=await githubRequest(env,'/repos/'+owner+'/'+repo+'/pulls',{method:'POST',body:JSON.stringify({title:args.title,body:args.body??'',head:args.head,base:'main',draft:true})});
    await audit(env,agent.id,'mcp.pull_request.create',data.html_url,'success',requestId,{number:data.number,head:args.head}); return {number:data.number,url:data.html_url,draft:data.draft};
  }
  throw new Error('unknown_tool');
}

function createMcpServer(env, agent, requestId) {
  const server=new McpServer({name:'yara-universal-gateway',version:'0.3.0'});
  const register=(name,description,inputSchema)=>server.registerTool(name,{description,inputSchema},async(args)=>{
    try { const result=await callTool(env,agent,name,args,requestId); return {content:[{type:'text',text:JSON.stringify(result)}]}; }
    catch(error) { await audit(env,agent.id,'mcp.tool.call',name,'error',requestId,{code:error.message}).catch(()=>{}); return {content:[{type:'text',text:error.message}],isError:true}; }
  });
  register('yara_identity','Return the authenticated YARA agent identity.',z.object({}).strict());
  register('yara_task_create','Create a durable task for the YARA agent pool.',z.object({title:z.string().min(3).max(200),description:z.string().min(1).max(20000),assignedTo:z.string().regex(/^[a-z][a-z0-9-]{1,47}$/).nullable().optional(),maxAttempts:z.number().int().min(1).max(10).optional()}).strict());
  register('yara_tasks_list','List tasks visible to this authenticated agent.',z.object({status:z.enum(['queued','running','completed','failed','cancelled']).optional()}).strict());
  register('yara_task_claim','Atomically claim the next queued task with a three-minute lease.',z.object({}).strict());
  register('yara_task_heartbeat','Extend the lease for a running task assigned to this agent.',z.object({taskId:z.string().uuid()}).strict());
  register('yara_task_complete','Submit a result or failure for an assigned task.',z.object({taskId:z.string().uuid(),outcome:z.enum(['completed','failed']).optional(),result:z.unknown().optional()}).strict());
  register('yara_message_send','Send a durable direct or broadcast message.',z.object({recipientId:z.string().regex(/^[a-z][a-z0-9-]{1,47}$/).nullable().optional(),taskId:z.string().uuid().optional(),kind:z.enum(['message','task_update','result','request']).optional(),body:z.unknown(),idempotencyKey:z.string().max(128).optional()}).strict());
  register('yara_message_inbox','Read direct and broadcast messages for this agent.',z.object({since:z.string().datetime().optional()}).strict());
  register('yara_message_ack','Acknowledge a delivered message.',z.object({messageId:z.string().uuid()}).strict());
  register('yara_read_file','Read a file from the configured YARA repository.',z.object({owner:z.string(),repo:z.string(),path:z.string().min(1).max(400)}).strict());
  register('yara_create_branch','Create an agent-scoped branch based on main.',z.object({owner:z.string(),repo:z.string(),name:z.string().regex(/^[a-z0-9][a-z0-9-]{1,79}$/)}).strict());
  register('yara_write_file','Write a file only on the authenticated agent branch.',z.object({owner:z.string(),repo:z.string(),path:z.string().min(1).max(400),branch:z.string().min(1).max(200),content:z.string().max(500000),message:z.string().min(1).max(200),sha:z.string().regex(/^[a-f0-9]{40}$/).optional()}).strict());
  register('yara_create_draft_pr','Open a draft pull request; never merges.',z.object({owner:z.string(),repo:z.string(),head:z.string().min(1).max(200),title:z.string().min(1).max(200),body:z.string().max(10000).optional()}).strict());
  return server;
}

async function handleMcp(request, env, agent) {
  const requestId=request.headers.get('cf-ray') ?? uuid();
  const handler=createMcpHandler(()=>createMcpServer(env,agent,requestId),{legacy:'stateless',responseMode:'auto',maxRequestBodySize:1024*1024});
  return handler.fetch(request);
}

async function handleApi(request, env, ctx) {
  const url=new URL(request.url), path=url.pathname, method=request.method, requestId=request.headers.get('cf-ray') ?? uuid();
  if (path==='/healthz'&&method==='GET') {
    await env.DB.prepare('SELECT 1 AS ok').first(); return json({status:'ok',service:'yara-universal-gateway',runtime:'cloudflare-workers'});
  }
  if (path==='/v1/admin/agents'&&method==='POST') {
    const supplied=parseBearer(request.headers.get('authorization'));
    if (!supplied||!safeEqual(supplied,env.YARA_BOOTSTRAP_TOKEN)) { await audit(env,null,'agent.provision','agent-registry','denied',requestId); return err('unauthorized',401); }
    if (typeof env.YARA_BOOTSTRAP_TOKEN!=='string'||env.YARA_BOOTSTRAP_TOKEN.length<32) return err('bootstrap_not_configured',503);
    const body=await parseJson(request), id=body.id, displayName=body.displayName;
    if (!validAgentId(id)||typeof displayName!=='string'||!displayName.trim()||displayName.length>100) return err('invalid_agent',400);
    const token=issueAgentToken();
    try { await env.DB.prepare('INSERT INTO yara_agents(id,display_name,token_hash) VALUES(?,?,?)').bind(id,displayName.trim(),await hashToken(token)).run(); }
    catch { return err('agent_id_already_exists',409); }
    await audit(env,'owner','agent.provision',id,'success',requestId,{capabilities:['project:read','branch:write','pull_request:create']});
    return json({id,token,warning:'Store this token securely. It will not be shown again.'},201);
  }
  const adminMatch=path.match(/^\/v1\/admin\/agents\/([a-z][a-z0-9-]{1,47})\/(rotate|revoke)$/);
  if (adminMatch&&(method==='POST')) {
    const supplied=parseBearer(request.headers.get('authorization')); if (!supplied||!safeEqual(supplied,env.YARA_BOOTSTRAP_TOKEN)) return err('unauthorized',401);
    const id=adminMatch[1], action=adminMatch[2];
    if (action==='rotate') { const token=issueAgentToken(); const result=await env.DB.prepare('UPDATE yara_agents SET token_hash=?,active=1,revoked_at=NULL WHERE id=? RETURNING id').bind(await hashToken(token),id).first(); if (!result) return err('agent_not_found',404); await audit(env,'owner','agent.token.rotate',id,'success',requestId); return json({id,token,warning:'Store this token securely. It will not be shown again.'}); }
    const result=await env.DB.prepare('UPDATE yara_agents SET active=0,revoked_at=? WHERE id=? AND active=1 RETURNING id').bind(nowIso(),id).first(); if (!result) return err('agent_not_found',404); await audit(env,'owner','agent.revoke',id,'success',requestId); return json({id,revoked:true});
  }
  const agent=await authenticate(request,env);
  if (!agent) { await audit(env,null,'agent.authenticate','gateway','denied',requestId).catch(()=>{}); return err('unauthorized',401); }
  if (path==='/v1/agents/me'&&method==='GET') { await audit(env,agent.id,'agent.identity.read',agent.id,'success',requestId); return json({id:agent.id,displayName:agent.display_name,status:'active'}); }
  const branchMatch=path.match(/^\/v1\/projects\/([^/]+)\/([^/]+)\/branches$/);
  if (branchMatch&&method==='POST') {
    const owner=decodeURIComponent(branchMatch[1]),repo=decodeURIComponent(branchMatch[2]); if (owner+'/'+repo!==env.GITHUB_REPOSITORY) return err('repository_not_allowed',403);
    const body=await parseJson(request); if (typeof body.name!=='string'||!/^[a-z0-9][a-z0-9-]{1,79}$/.test(body.name)) return err('invalid_branch_name',400);
    const branch='agents/'+agent.id+'/'+body.name; assertAgentBranch(agent.id,branch);
    const base=await githubRequest(env,'/repos/'+owner+'/'+repo+'/git/ref/heads/main');
    const created=await githubRequest(env,'/repos/'+owner+'/'+repo+'/git/refs',{method:'POST',body:JSON.stringify({ref:'refs/heads/'+branch,sha:base.object.sha})});
    await audit(env,agent.id,'branch.create',owner+'/'+repo+'/'+branch,'success',requestId,{base:'main',baseSha:base.object.sha,commitSha:created.object.sha}); return json({branch,base:'main',commitSha:created.object.sha},201);
  }
  const fileMatch=path.match(/^\/v1\/projects\/([^/]+)\/([^/]+)\/files$/);
  if (fileMatch&&method==='GET') {
    const owner=decodeURIComponent(fileMatch[1]),repo=decodeURIComponent(fileMatch[2]),filePath=url.searchParams.get('path'); validateRepo(env,owner,repo,filePath);
    const data=await githubRequest(env,'/repos/'+owner+'/'+repo+'/contents/'+filePath.split('/').map(encodeURIComponent).join('/')); if (Array.isArray(data)||!data.content) return err('file_not_found',404);
    const bytes=Uint8Array.from(atob(data.content.replace(/\n/g,'')),c=>c.charCodeAt(0)); await audit(env,agent.id,'project.file.read',owner+'/'+repo+'/'+filePath,'success',requestId,{sha:data.sha});
    return json({path:filePath,sha:data.sha,encoding:data.encoding,content:new TextDecoder().decode(bytes)});
  }
  if (fileMatch&&method==='PUT') {
    const owner=decodeURIComponent(fileMatch[1]),repo=decodeURIComponent(fileMatch[2]),body=await parseJson(request); validateRepo(env,owner,repo,body.path);
    try { assertAgentBranch(agent.id,body.branch); assertWritablePath(body.path); } catch(e) { await audit(env,agent.id,'project.file.write',owner+'/'+repo+'/'+body.path,'denied',requestId,{reason:e.message}); return err(e.message,403); }
    if (typeof body.content!=='string'||byteLength(body.content)>500000||typeof body.message!=='string'||!body.message.trim()||body.message.length>200) return err('invalid_write_payload',400);
    const payload={message:body.message,content:encodeBase64(new TextEncoder().encode(body.content)),branch:body.branch}; if (typeof body.sha==='string'&&/^[a-f0-9]{40}$/.test(body.sha)) payload.sha=body.sha;
    const data=await githubRequest(env,'/repos/'+owner+'/'+repo+'/contents/'+body.path.split('/').map(encodeURIComponent).join('/'),{method:'PUT',body:JSON.stringify(payload)});
    await audit(env,agent.id,'project.file.write',owner+'/'+repo+'/'+body.path,'success',requestId,{branch:body.branch,commitSha:data.commit?.sha,contentSha:data.content?.sha}); return json({path:body.path,branch:body.branch,commitSha:data.commit?.sha,contentSha:data.content?.sha},201);
  }
  const prMatch=path.match(/^\/v1\/projects\/([^/]+)\/([^/]+)\/pull-requests$/);
  if (prMatch&&method==='POST') {
    const owner=decodeURIComponent(prMatch[1]),repo=decodeURIComponent(prMatch[2]),body=await parseJson(request); if (owner+'/'+repo!==env.GITHUB_REPOSITORY) return err('repository_not_allowed',403);
    if (typeof body.title!=='string'||!body.title.trim()||body.title.length>200||typeof(body.body??'')!=='string'||(body.body??'').length>10000||(body.base??'main')!=='main') return err('invalid_pull_request_payload',400);
    try { assertAgentBranch(agent.id,body.head); } catch(e) { await audit(env,agent.id,'pull_request.create',owner+'/'+repo,'denied',requestId,{reason:e.message}); return err(e.message,403); }
    const data=await githubRequest(env,'/repos/'+owner+'/'+repo+'/pulls',{method:'POST',body:JSON.stringify({title:body.title,body:body.body??'',head:body.head,base:'main',draft:true})});
    await audit(env,agent.id,'pull_request.create',data.html_url,'success',requestId,{number:data.number,head:body.head}); return json({number:data.number,url:data.html_url,draft:data.draft,state:data.state},201);
  }
  if (path==='/v1/tasks'&&method==='POST') {
    const body=await parseJson(request), parsed=validateTaskPayload(body); if (!parsed.ok) return err(parsed.error,400); const v=parsed.value;
    if (v.assignedTo&&!(await getRow(env.DB.prepare('SELECT id FROM yara_agents WHERE id=? AND active=1').bind(v.assignedTo)))) return err('assignee_not_found',404);
    const id=uuid(); await env.DB.prepare('INSERT INTO yara_tasks(id,title,description,created_by,assigned_to,max_attempts) VALUES(?,?,?,?,?,?)').bind(id,v.title,v.description,agent.id,v.assignedTo,v.maxAttempts).run();
    await audit(env,agent.id,'task.create',id,'success',requestId,{assignedTo:v.assignedTo}); return json({task:normalizeTask(await getRow(env.DB.prepare('SELECT * FROM yara_tasks WHERE id=?').bind(id)))},201);
  }
  if (path==='/v1/tasks'&&method==='GET') {
    const state=url.searchParams.get('status'); if (state&&!['queued','running','completed','failed','cancelled'].includes(state)) return err('invalid_status',400);
    const sql='SELECT id,title,description,created_by,assigned_to,status,attempts,max_attempts,lease_until,result,created_at,updated_at,completed_at FROM yara_tasks WHERE (created_by=? OR assigned_to=? OR (assigned_to IS NULL AND status=\'queued\'))'+(state?' AND status=?':'')+' ORDER BY created_at DESC LIMIT 100';
    const q=state?env.DB.prepare(sql).bind(agent.id,agent.id,state):env.DB.prepare(sql).bind(agent.id,agent.id); return json({tasks:(await q.all()).results.map(normalizeTask)});
  }
  if (path==='/v1/tasks/claim'&&method==='POST') { const result=await callTool(env,agent,'yara_task_claim',{},requestId); return json(result); }
  const heartbeat=path.match(/^\/v1\/tasks\/([0-9a-f-]+)\/heartbeat$/);
  if (heartbeat&&method==='POST') { const result=await callTool(env,agent,'yara_task_heartbeat',{taskId:heartbeat[1]},requestId); return json(result); }
  const complete=path.match(/^\/v1\/tasks\/([0-9a-f-]+)\/complete$/);
  if (complete&&method==='POST') { const body=await parseJson(request); return json(await callTool(env,agent,'yara_task_complete',{taskId:complete[1],outcome:body.outcome,result:body.result},requestId)); }
  if (path==='/v1/messages'&&method==='POST') { const body=await parseJson(request); return json(await callTool(env,agent,'yara_message_send',body,requestId),201); }
  if (path==='/v1/messages/inbox'&&method==='GET') { return json(await callTool(env,agent,'yara_message_inbox',{since:url.searchParams.get('since')??undefined},requestId)); }
  const ack=path.match(/^\/v1\/messages\/([0-9a-f-]+)\/ack$/);
  if (ack&&method==='POST') { return json(await callTool(env,agent,'yara_message_ack',{messageId:ack[1]},requestId)); }
  const taskMessages=path.match(/^\/v1\/tasks\/([0-9a-f-]+)\/messages$/);
  if (taskMessages&&method==='GET') { const task=await getRow(env.DB.prepare('SELECT created_by,assigned_to FROM yara_tasks WHERE id=?').bind(taskMessages[1])); if(!task||![task.created_by,task.assigned_to].includes(agent.id)) return err('task_not_found',404); const rows=await env.DB.prepare('SELECT * FROM yara_messages WHERE task_id=? ORDER BY created_at ASC LIMIT 100').bind(taskMessages[1]).all(); return json({messages:rows.results.map(normalizeMessage)}); }
  return err('not_found',404);
}

export default {
  async fetch(request, env, ctx) {
    try {
      const url=new URL(request.url);
      if (url.pathname==='/mcp') {
        const agent=await authenticate(request,env);
        if (!agent) { await audit(env,null,'agent.authenticate','mcp','denied',request.headers.get('cf-ray')??null).catch(()=>{}); return err('unauthorized',401); }
        return handleMcp(request,env,agent);
      }
      return await handleApi(request,env,ctx);
    } catch(error) {
      const status=Number.isInteger(error.status)?error.status:500;
      const code=error.message==='github_app_not_configured'?'github_app_not_configured':status>=500?'internal_error':error.message;
      return json({error:code},status);
    }
  }
};
