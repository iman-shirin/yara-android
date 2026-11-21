import test from 'node:test';
import assert from 'node:assert/strict';
import { hashToken, issueAgentToken, safeEqual, validAgentId, validRepoPath, assertAgentBranch, assertWritablePath } from '../src/security.mjs';
import { validateTaskPayload, validateMessagePayload } from '../src/collaboration.mjs';

test('agent tokens are unique, random-looking and stored as SHA-256 hashes', async () => {
  const first=issueAgentToken(), second=issueAgentToken();
  assert.equal(first.length,64); assert.notEqual(first,second);
  const digest=await hashToken(first); assert.equal(digest.length,64); assert.notEqual(digest,first);
});

test('constant-time equality handles equal and unequal values', () => {
  assert.equal(safeEqual('same','same'),true); assert.equal(safeEqual('same','other'),false); assert.equal(safeEqual('short','longer'),false);
});

test('agent IDs and repository paths reject unsafe values', () => {
  assert.equal(validAgentId('agent-claude'),true); assert.equal(validAgentId('../owner'),false);
  assert.equal(validRepoPath('docs/architecture.md'),true); assert.equal(validRepoPath('../secrets'),false); assert.equal(validRepoPath('/etc/passwd'),false); assert.equal(validRepoPath('a//b'),false);
});

test('branch policy allows only the authenticated agent namespace', () => {
  assert.equal(assertAgentBranch('agent-claude','agents/agent-claude/task-1'),true);
  assert.throws(()=>assertAgentBranch('agent-claude','main'),/protected_branch/);
  assert.throws(()=>assertAgentBranch('agent-claude','agents/agent-gemini/task-1'),/branch_out_of_scope/);
});

test('write policy blocks runtime, CI, deployment and secret paths', () => {
  assert.equal(assertWritablePath('docs/architecture.md'),true);
  for (const path of ['.github/workflows/release.yml','gateway/cloudflare/src/index.mjs','gateway/src/server.mjs','.railway/railway.ts','secrets/token.json','gateway/.env.example','app/local.properties']) assert.throws(()=>assertWritablePath(path),/protected_path/);
});

test('task payload validation enforces limits and defaults', () => {
  const valid=validateTaskPayload({title:'Review design',description:'Check the runtime.'}); assert.equal(valid.ok,true); assert.equal(valid.value.maxAttempts,3);
  assert.equal(validateTaskPayload({title:'x',description:'no'}).error,'invalid_title');
  assert.equal(validateTaskPayload({title:'Valid',description:'x'.repeat(20001)}).error,'invalid_description');
  assert.equal(validateTaskPayload({title:'Valid',description:'ok',assignedTo:'../admin'}).error,'invalid_assignee');
});

test('message validation rejects invalid recipients, IDs, kinds and oversized bodies', () => {
  assert.equal(validateMessagePayload({recipientId:'agent-gemini',body:{text:'hi'}}).ok,true);
  assert.equal(validateMessagePayload({recipientId:'../admin',body:'x'}).error,'invalid_recipient');
  assert.equal(validateMessagePayload({taskId:'not-a-uuid',body:'x'}).error,'invalid_task_id');
  assert.equal(validateMessagePayload({kind:'run-shell',body:'x'}).error,'invalid_message_kind');
  assert.equal(validateMessagePayload({body:'x'.repeat(50001)}).error,'invalid_message_body');
});
