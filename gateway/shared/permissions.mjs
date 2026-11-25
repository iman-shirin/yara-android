export const VALID_AGENT_SCOPES = Object.freeze([
  'project:read',
  'task:read',
  'task:write',
  'message:read',
  'message:write',
  'branch:write',
  'pull_request:create'
]);
export const DEFAULT_AGENT_SCOPES = Object.freeze(['project:read']);

const TOOL_SCOPES = Object.freeze({
  yara_identity: 'project:read',
  yara_task_create: 'task:write',
  yara_tasks_list: 'task:read',
  yara_task_claim: 'task:write',
  yara_task_heartbeat: 'task:write',
  yara_task_complete: 'task:write',
  yara_message_send: 'message:write',
  yara_message_inbox: 'message:read',
  yara_message_ack: 'message:write',
  yara_read_file: 'project:read',
  yara_create_branch: 'branch:write',
  yara_write_file: 'branch:write',
  yara_create_draft_pr: 'pull_request:create'
});

export function normalizeAgentScopes(value) {
  if (value === undefined) return [...DEFAULT_AGENT_SCOPES];
  if (!Array.isArray(value) || value.length > VALID_AGENT_SCOPES.length) return null;
  if (value.some((scope) => typeof scope !== 'string' || !VALID_AGENT_SCOPES.includes(scope))) return null;
  return [...new Set(value)].sort();
}

export function requiredToolScope(toolName) {
  return TOOL_SCOPES[toolName] ?? null;
}

export function agentHasScope(agent, scope) {
  return Boolean(scope && Array.isArray(agent?.scopes) && agent.scopes.includes(scope));
}

export function requiredApiScope(method, path) {
  if (method === 'GET' && path === '/v1/agents/me') return 'project:read';
  if (method === 'GET' && /^\/v1\/projects\/[^/]+\/[^/]+\/files$/.test(path)) return 'project:read';
  if (method === 'POST' && /^\/v1\/projects\/[^/]+\/[^/]+\/branches$/.test(path)) return 'branch:write';
  if (method === 'PUT' && /^\/v1\/projects\/[^/]+\/[^/]+\/files$/.test(path)) return 'branch:write';
  if (method === 'POST' && /^\/v1\/projects\/[^/]+\/[^/]+\/pull-requests$/.test(path)) return 'pull_request:create';
  if (method === 'GET' && path === '/v1/tasks') return 'task:read';
  if (method === 'POST' && /^\/v1\/tasks(?:\/|$)/.test(path)) return 'task:write';
  if (method === 'POST' && path === '/v1/messages') return 'message:write';
  if (method === 'GET' && path === '/v1/messages/inbox') return 'message:read';
  if (method === 'POST' && /^\/v1\/messages\/[^/]+\/ack$/.test(path)) return 'message:write';
  if (method === 'GET' && /^\/v1\/tasks\/[^/]+\/messages$/.test(path)) return 'message:read';
  return null;
}

