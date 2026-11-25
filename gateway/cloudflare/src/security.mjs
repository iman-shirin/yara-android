const encoder = new TextEncoder();

export async function hashToken(token) {
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(String(token ?? '')));
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, '0')).join('');
}

export function issueAgentToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return [...bytes].map((value) => value.toString(16).padStart(2, '0')).join('');
}

export function safeEqual(left, right) {
  const a = encoder.encode(String(left ?? ''));
  const b = encoder.encode(String(right ?? ''));
  let diff = a.length ^ b.length;
  const size = Math.max(a.length, b.length);
  for (let i = 0; i < size; i += 1) diff |= (a[i] ?? 0) ^ (b[i] ?? 0);
  return diff === 0;
}

export function validAgentId(value) { return typeof value === 'string' && /^[a-z][a-z0-9-]{1,47}$/.test(value); }
export function validRepoPart(value) { return typeof value === 'string' && /^[A-Za-z0-9_.-]{1,100}$/.test(value) && value !== '.' && value !== '..'; }

export function validRepoPath(value) {
  if (typeof value !== 'string' || value.length < 1 || value.length > 400 || value.startsWith('/') || value.includes('\\') || value.includes('\0')) return false;
  return value.split('/').every((part) => part.length > 0 && part !== '.' && part !== '..');
}

export function assertAgentBranch(agentId, branch) {
  if (typeof branch !== 'string' || branch.length > 200 || branch.startsWith('-')) throw new Error('invalid_branch');
  if (['main', 'master'].includes(branch) || branch.startsWith('main/') || branch.startsWith('master/')) throw new Error('protected_branch');
  const prefix = `agents/${agentId}/`;
  if (!branch.startsWith(prefix) || branch.length <= prefix.length) throw new Error('branch_out_of_scope');
  if (branch.split('/').some((part) => !part || part === '.' || part === '..' || part.startsWith('.'))) throw new Error('invalid_branch');
  return true;
}

export function assertWritablePath(path) {
  if (!validRepoPath(path)) throw new Error('invalid_resource_path');
  const normalized = path.toLowerCase();
  const blocked = [
    '.github/workflows/', '.github/codeowners', '.github/rulesets/', '.github/actions/',
    '.devcontainer/', '.railway/', 'agents.md', '.github/copilot-instructions.md',
    '.github/instructions/', '.git/', '.gitmodules', 'gateway/src/server.mjs',
    'gateway/src/security.mjs', 'gateway/src/collaboration.mjs', 'gateway/src/index.mjs',
    'gateway/cloudflare/', 'gateway/scripts/', 'gateway/package.json', 'gateway/package-lock.json',
    'gateway/dockerfile', 'gateway/.env', 'gateway/.env.example', 'secrets/', '.env'
  ];
  if (blocked.some((prefix) => normalized === prefix || normalized.startsWith(prefix))) throw new Error('protected_path');
  if (normalized.split('/').some((segment) => segment === '.env' || segment.startsWith('.env.'))) throw new Error('protected_path');
  if (['.pem','.key','.p12','.jks','.keystore'].some((suffix) => normalized.endsWith(suffix)) || normalized.endsWith('local.properties')) throw new Error('protected_path');
  return true;
}

export function parseBearer(header) {
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return null;
  return header.slice(7).trim() || null;
}

export function encodeBase64(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}
export function decodeBase64(value) {
  const binary = atob(value);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}
export function base64Url(bytes) { return encodeBase64(bytes).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_'); }
