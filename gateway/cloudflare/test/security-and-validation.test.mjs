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


test('MCP endpoint serves a stateless initialize request through the web handler', async () => {
  const { default: worker } = await import('../src/index.mjs');
  const token = 'test-agent-token';
  const { hashToken } = await import('../src/security.mjs');
  const digest = await hashToken(token);
  const DB = {
    prepare(sql) {
      const statement = {
        values: [],
        bind(...values) { this.values = values; return this; },
        async first() {
          if (sql.includes('FROM yara_agents WHERE token_hash=?')) {
            return this.values[0] === digest ? { id: 'agent-test', display_name: 'Test Agent' } : null;
          }
          return null;
        },
        async run() { return { success: true, meta: { changes: 1 } }; },
        async all() { return { results: [] }; }
      };
      return statement;
    },
    async batch() { return []; }
  };
  const request = new Request('https://yara.example.workers.dev/mcp', {
    method: 'POST',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream'
    },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        protocolVersion: '2025-03-26',
        capabilities: {},
        clientInfo: { name: 'yara-smoke-test', version: '1.0.0' }
      }
    })
  });
  const response = await worker.fetch(request, { DB, YARA_BOOTSTRAP_TOKEN: 'x'.repeat(40), GITHUB_REPOSITORY: 'iman-shirin/yara-android' }, {});
  assert.equal(response.status, 200, await response.clone().text());
  assert.match(response.headers.get('content-type') ?? '', /application\/json|text\/event-stream/);
});


test('admin bootstrap routes reject missing or weak owner secrets before database access', async () => {
  const { default: worker } = await import('../src/index.mjs');
  const DB = { prepare() { throw new Error('database must not be accessed'); } };
  for (const pathname of ['/v1/admin/agents', '/v1/admin/agents/agent-test/revoke', '/v1/admin/agents/agent-test/rotate']) {
    const response = await worker.fetch(new Request('https://yara.example.workers.dev' + pathname, {
      method: 'POST',
      headers: { authorization: 'Bearer weak-secret', 'content-type': 'application/json' },
      body: JSON.stringify({ id: 'agent-new', displayName: 'New Agent' })
    }), { DB, YARA_BOOTSTRAP_TOKEN: 'weak-secret' }, {});
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: 'bootstrap_not_configured' });
  }
});


test('REST validation errors return client status codes instead of internal errors', async () => {
  const { default: worker } = await import('../src/index.mjs');
  const { hashToken } = await import('../src/security.mjs');
  const token = 'rest-validation-agent-token';
  const digest = await hashToken(token);
  const DB = {
    prepare(sql) {
      const statement = {
        values: [],
        bind(...values) { this.values = values; return this; },
        async first() {
          if (sql.includes('FROM yara_agents WHERE token_hash=?')) {
            return this.values[0] === digest ? { id: 'agent-test', display_name: 'Test Agent' } : null;
          }
          return null;
        },
        async run() { return { success: true, meta: { changes: 1 } }; },
        async all() { return { results: [] }; }
      };
      return statement;
    }
  };
  const response = await worker.fetch(new Request('https://yara.example.workers.dev/v1/messages', {
    method: 'POST',
    headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' },
    body: JSON.stringify({ recipientId: '../invalid', body: 'hello' })
  }), { DB, GITHUB_REPOSITORY: 'iman-shirin/yara-android' }, {});
  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { error: 'invalid_recipient' });
});


test('stateless MCP endpoint exposes the expected YARA tool registry', async () => {
  const { default: worker } = await import('../src/index.mjs');
  const token = 'mcp-tools-list-agent-token';
  const { hashToken } = await import('../src/security.mjs');
  const digest = await hashToken(token);
  const DB = {
    prepare(sql) {
      const statement = {
        values: [],
        bind(...values) { this.values = values; return this; },
        async first() {
          if (sql.includes('FROM yara_agents WHERE token_hash=?')) {
            return this.values[0] === digest ? { id: 'agent-test', display_name: 'Test Agent' } : null;
          }
          return null;
        },
        async run() { return { success: true, meta: { changes: 1 } }; },
        async all() { return { results: [] }; }
      };
      return statement;
    },
    async batch() { return []; }
  };
  const response = await worker.fetch(new Request('https://yara.example.workers.dev/mcp', {
    method: 'POST',
    headers: {
      authorization: 'Bearer ' + token,
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream'
    },
    body: JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} })
  }), { DB, YARA_BOOTSTRAP_TOKEN: 'x'.repeat(40), GITHUB_REPOSITORY: 'iman-shirin/yara-android' }, {});
  assert.equal(response.status, 200, await response.clone().text());
  assert.match(response.headers.get('content-type') ?? '', /text\\/event-stream|application\\/json/);
  const wire = await response.text();
  const dataLine = wire.split(/\\r?\\n/).find((line) => line.startsWith('data:'));
  assert.ok(dataLine, wire);
  const body = JSON.parse(dataLine.slice(5).trim());
  assert.equal(body.jsonrpc, '2.0');
  assert.ok(body.result.tools.some((tool) => tool.name === 'yara_task_claim'));
  assert.ok(body.result.tools.some((tool) => tool.name === 'yara_message_ack'));
});
