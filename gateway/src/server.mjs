import pg from "pg";
import { createMcpFastifyApp } from "@modelcontextprotocol/fastify";
import { toNodeHandler } from "@modelcontextprotocol/node";
import { createMcpHandler, McpServer } from "@modelcontextprotocol/server";
import * as z from "zod/v4";
import { createPrivateKey, sign } from "node:crypto";
import { assertAgentBranch, assertWritablePath, hashToken, issueAgentToken, safeEqual, validAgentId, validRepoPart, validRepoPath } from "./security.mjs";
import { migrateCollaboration, registerCollaborationRoutes, validateTaskPayload, validateMessagePayload } from "./collaboration.mjs";

const { Pool } = pg;
const env = process.env;
const required = ["DATABASE_URL", "YARA_BOOTSTRAP_TOKEN", "GITHUB_APP_ID", "GITHUB_INSTALLATION_ID", "GITHUB_APP_PRIVATE_KEY_BASE64", "GITHUB_REPOSITORY"];
for (const key of required) {
  if (!env[key]) throw new Error(`Missing required environment variable: ${key}`);
}
if (env.YARA_BOOTSTRAP_TOKEN.length < 32) throw new Error("YARA_BOOTSTRAP_TOKEN must contain at least 32 characters");

const pool = new Pool({
  connectionString: env.DATABASE_URL,
  ssl: env.PGSSLMODE === "disable" ? false : { rejectUnauthorized: env.PGSSL_REJECT_UNAUTHORIZED !== "false" },
  max: Number(env.PG_POOL_MAX ?? 10),
  connectionTimeoutMillis: 5000,
  idleTimeoutMillis: 30000
});
const configuredHosts = (env.GATEWAY_ALLOWED_HOSTS ?? "")
  .split(",").map((host) => host.trim().toLowerCase()).filter(Boolean);
if (env.NODE_ENV === "production" && configuredHosts.length === 0) {
  throw new Error("GATEWAY_ALLOWED_HOSTS must include the public Gateway hostname in production");
}
const allowedHosts = [...new Set(["localhost", "127.0.0.1", ...configuredHosts])];
const app = createMcpFastifyApp({ host: env.HOST ?? "0.0.0.0", allowedHosts });
app.server.requestTimeout = 15000;

const privateKey = createPrivateKey(Buffer.from(env.GITHUB_APP_PRIVATE_KEY_BASE64, "base64").toString("utf8"));
let installationToken = null;
let installationTokenExpiresAt = 0;

async function migrate() {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS yara_agents (
      id text PRIMARY KEY,
      display_name text NOT NULL,
      token_hash text NOT NULL UNIQUE,
      active boolean NOT NULL DEFAULT true,
      created_at timestamptz NOT NULL DEFAULT now(),
      revoked_at timestamptz
    );
    CREATE TABLE IF NOT EXISTS yara_audit (
      id bigserial PRIMARY KEY,
      occurred_at timestamptz NOT NULL DEFAULT now(),
      agent_id text,
      action text NOT NULL,
      resource text NOT NULL,
      outcome text NOT NULL,
      request_id text,
      details jsonb NOT NULL DEFAULT '{}'::jsonb
    );
    CREATE INDEX IF NOT EXISTS yara_audit_agent_time_idx ON yara_audit(agent_id, occurred_at DESC);
    CREATE OR REPLACE FUNCTION yara_audit_immutable() RETURNS trigger AS $$
    BEGIN RAISE EXCEPTION 'yara_audit is append-only'; END;
    $$ LANGUAGE plpgsql;
    DROP TRIGGER IF EXISTS yara_audit_no_mutation ON yara_audit;
    CREATE TRIGGER yara_audit_no_mutation BEFORE UPDATE OR DELETE ON yara_audit
      FOR EACH ROW EXECUTE FUNCTION yara_audit_immutable();
  `);
}

async function audit(agentId, action, resource, outcome, requestId, details = {}) {
  await pool.query(
    "INSERT INTO yara_audit(agent_id, action, resource, outcome, request_id, details) VALUES($1,$2,$3,$4,$5,$6)",
    [agentId ?? null, action, resource, outcome, requestId ?? null, JSON.stringify(details)]
  );
}

function parseBearer(header) {
  if (typeof header !== "string" || !header.startsWith("Bearer ")) return null;
  return header.slice(7).trim() || null;
}

async function authenticate(request, reply) {
  const token = parseBearer(request.headers.authorization);
  if (!token) return reply.code(401).send({ error: "unauthorized" });
  const result = await pool.query(
    "SELECT id, display_name FROM yara_agents WHERE token_hash=$1 AND active=true",
    [hashToken(token)]
  );
  if (result.rowCount !== 1) {
    await audit(null, "agent.authenticate", "gateway", "denied", request.id);
    return reply.code(401).send({ error: "unauthorized" });
  }
  request.agent = result.rows[0];
}

function appJwt() {
  const now = Math.floor(Date.now() / 1000);
  const header = Buffer.from(JSON.stringify({ alg: "RS256", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(JSON.stringify({
    iat: now - 30,
    exp: now + 540,
    iss: env.GITHUB_APP_ID
  })).toString("base64url");
  const unsigned = `${header}.${payload}`;
  return `${unsigned}.${sign("RSA-SHA256", Buffer.from(unsigned), privateKey).toString("base64url")}`;
}

async function githubToken() {
  if (installationToken && Date.now() < installationTokenExpiresAt - 60000) return installationToken;
  const response = await fetch(`https://api.github.com/app/installations/${encodeURIComponent(env.GITHUB_INSTALLATION_ID)}/access_tokens`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${appJwt()}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28"
    }
  });
  if (!response.ok) throw new Error(`github_app_token_failed_${response.status}`);
  const data = await response.json();
  installationToken = data.token;
  installationTokenExpiresAt = Date.parse(data.expires_at);
  return installationToken;
}

async function githubRequest(path, options = {}) {
  const token = await githubToken();
  const response = await fetch(`https://api.github.com${path}`, {
    ...options,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...options.headers
    }
  });
  const body = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) {
    const error = new Error(`github_api_error_${response.status}`);
    error.statusCode = response.status === 404 ? 404 : response.status === 409 ? 409 : 502;
    throw error;
  }
  return body;
}

function validateRepo(owner, repo, path) {
  if (!validRepoPart(owner) || !validRepoPart(repo) || !validRepoPath(path)) {
    const error = new Error("invalid_resource_path");
    error.statusCode = 400;
    throw error;
  }
  if (`${owner}/${repo}` !== env.GITHUB_REPOSITORY) {
    const error = new Error("repository_not_allowed");
    error.statusCode = 403;
    throw error;
  }
}

async function callMcpTool(agent, name, args, requestId) {
  if (name === "yara_task_create") {
    const parsed = validateTaskPayload(args);
    if (!parsed.ok) throw new Error(parsed.error);
    const { title, description, assignedTo, maxAttempts } = parsed.value;
    if (assignedTo) {
      const exists = await pool.query("SELECT id FROM yara_agents WHERE id=$1 AND active=true", [assignedTo]);
      if (!exists.rowCount) throw new Error("assignee_not_found");
    }
    const { randomUUID } = await import("node:crypto");
    const result = await pool.query(
      "INSERT INTO yara_tasks(id,title,description,created_by,assigned_to,max_attempts) VALUES($1,$2,$3,$4,$5,$6) RETURNING *",
      [randomUUID(), title, description, agent.id, assignedTo, maxAttempts]
    );
    await audit(agent.id, "task.create", result.rows[0].id, "success", requestId, { assignedTo });
    return { task: result.rows[0] };
  }
  if (name === "yara_task_claim") {
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      await client.query("UPDATE yara_tasks SET status='failed',lease_until=NULL,completed_at=now(),updated_at=now() WHERE status='running' AND lease_until < now() AND attempts >= max_attempts");
      await client.query("UPDATE yara_tasks SET status='queued',assigned_to=NULL,lease_until=NULL,updated_at=now() WHERE status='running' AND lease_until < now() AND attempts < max_attempts");
      const selected = await client.query(
        "SELECT id FROM yara_tasks WHERE status='queued' AND (assigned_to IS NULL OR assigned_to=$1) AND attempts < max_attempts ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
        [agent.id]
      );
      if (!selected.rowCount) { await client.query("COMMIT"); return { task: null }; }
      const updated = await client.query(
        "UPDATE yara_tasks SET status='running',assigned_to=$2,attempts=attempts+1,lease_until=now()+interval '3 minutes',updated_at=now() WHERE id=$1 RETURNING *",
        [selected.rows[0].id, agent.id]
      );
      await client.query("COMMIT");
      await audit(agent.id, "task.claim", updated.rows[0].id, "success", requestId, { attempt: updated.rows[0].attempts });
      return { task: updated.rows[0] };
    } catch (error) { await client.query("ROLLBACK").catch(() => {}); throw error; }
    finally { client.release(); }
  }
  if (name === "yara_tasks_list") {
    const state = args.status;
    if (state && !["queued","running","completed","failed","cancelled"].includes(state)) throw new Error("invalid_status");
    const values = [agent.id];
    let filter = "(created_by=$1 OR assigned_to=$1 OR (assigned_to IS NULL AND status='queued'))";
    if (state) { values.push(state); filter += " AND status=$2"; }
    const result = await pool.query(
      `SELECT id,title,description,created_by,assigned_to,status,attempts,max_attempts,lease_until,result,created_at,updated_at,completed_at FROM yara_tasks WHERE ${filter} ORDER BY created_at DESC LIMIT 100`,
      values
    );
    return { tasks: result.rows };
  }
  if (name === "yara_task_heartbeat") {
    const result = await pool.query(
      "UPDATE yara_tasks SET lease_until=now()+interval '3 minutes',updated_at=now() WHERE id=$1 AND assigned_to=$2 AND status='running' RETURNING id,lease_until",
      [args.taskId, agent.id]
    );
    if (!result.rowCount) throw new Error("active_task_not_found");
    return result.rows[0];
  }
  if (name === "yara_task_complete") {
    const outcome = args.outcome ?? "completed";
    if (!["completed", "failed"].includes(outcome)) throw new Error("invalid_outcome");
    const resultBody = args.result ?? {};
    if (Buffer.byteLength(JSON.stringify(resultBody), "utf8") > 50000) throw new Error("result_too_large");
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      const task = await client.query("SELECT * FROM yara_tasks WHERE id=$1 FOR UPDATE", [args.taskId]);
      if (!task.rowCount || task.rows[0].assigned_to !== agent.id || task.rows[0].status !== "running") throw new Error("active_task_not_found");
      const retry = outcome === "failed" && task.rows[0].attempts < task.rows[0].max_attempts;
      const status = outcome === "completed" ? "completed" : retry ? "queued" : "failed";
      const updated = await client.query(
        "UPDATE yara_tasks SET status=$2,assigned_to=CASE WHEN $2='queued' THEN NULL ELSE assigned_to END,result=$3,lease_until=NULL,updated_at=now(),completed_at=CASE WHEN $2 IN ('completed','failed') THEN now() ELSE NULL END WHERE id=$1 RETURNING *",
        [args.taskId, status, JSON.stringify(resultBody)]
      );
      await client.query(
        "INSERT INTO yara_messages(id,task_id,sender_id,recipient_id,kind,body) VALUES(gen_random_uuid(),$1,$2,$3,'result',$4)",
        [args.taskId, agent.id, task.rows[0].created_by, JSON.stringify({ outcome, retryScheduled: retry, result: resultBody })]
      );
      await client.query("COMMIT");
      await audit(agent.id, "task.complete", args.taskId, "success", requestId, { status });
      return { task: updated.rows[0], retryScheduled: retry };
    } catch (error) { await client.query("ROLLBACK").catch(() => {}); throw error; }
    finally { client.release(); }
  }
  if (name === "yara_message_send") {
    const parsed = validateMessagePayload(args);
    if (!parsed.ok) throw new Error(parsed.error);
    const { recipientId, kind, body, idempotencyKey } = parsed.value;
    if (recipientId) {
      const exists = await pool.query("SELECT id FROM yara_agents WHERE id=$1 AND active=true", [recipientId]);
      if (!exists.rowCount) throw new Error("recipient_not_found");
    }
    if (idempotencyKey && (typeof idempotencyKey !== "string" || idempotencyKey.length > 128)) throw new Error("invalid_idempotency_key");
    const { randomUUID } = await import("node:crypto");
    const inserted = await pool.query(
      "INSERT INTO yara_messages(id,sender_id,recipient_id,kind,body,idempotency_key) VALUES($1,$2,$3,$4,$5,$6) RETURNING *",
      [randomUUID(), agent.id, recipientId, kind, JSON.stringify(body), idempotencyKey]
    );
    await audit(agent.id, "message.send", recipientId ?? "broadcast", "success", requestId, { messageId: inserted.rows[0].id });
    return { message: inserted.rows[0] };
  }
  if (name === "yara_message_inbox") {
    const result = await pool.query(
      "SELECT id,task_id,sender_id,recipient_id,kind,body,created_at,read_at FROM yara_messages WHERE (recipient_id=$1 OR recipient_id IS NULL) AND ($2::timestamptz IS NULL OR created_at>$2) ORDER BY created_at ASC LIMIT 100",
      [agent.id, args.since ?? null]
    );
    return { messages: result.rows };
  }
  if (name === "yara_message_ack") {
    const result = await pool.query(
      "UPDATE yara_messages SET read_at=now() WHERE id=$1 AND (recipient_id=$2 OR recipient_id IS NULL) RETURNING id,read_at",
      [args.messageId, agent.id]
    );
    if (!result.rowCount) throw new Error("message_not_found");
    await audit(agent.id, "message.ack", args.messageId, "success", requestId);
    return result.rows[0];
  }
  if (name === "yara_identity") {
    await audit(agent.id, "mcp.identity", agent.id, "success", requestId);
    return { id: agent.id, displayName: agent.display_name, status: "active" };
  }
  const { owner, repo } = args;
  if (!validRepoPart(owner) || !validRepoPart(repo) || `${owner}/${repo}` !== env.GITHUB_REPOSITORY) {
    throw new Error("repository_not_allowed");
  }
  if (name === "yara_read_file") {
    validateRepo(owner, repo, args.path);
    const data = await githubRequest(`/repos/${owner}/${repo}/contents/${args.path.split("/").map(encodeURIComponent).join("/")}`);
    if (Array.isArray(data) || !data.content) throw new Error("file_not_found");
    await audit(agent.id, "mcp.file.read", `${owner}/${repo}/${args.path}`, "success", requestId, { sha: data.sha });
    return { path: args.path, sha: data.sha, content: Buffer.from(data.content, "base64").toString("utf8") };
  }
  if (name === "yara_create_branch") {
    if (typeof args.name !== "string" || !/^[a-z0-9][a-z0-9-]{1,79}$/.test(args.name)) throw new Error("invalid_branch_name");
    const branch = `agents/${agent.id}/${args.name}`;
    assertAgentBranch(agent.id, branch);
    const base = await githubRequest(`/repos/${owner}/${repo}/git/ref/heads/main`);
    const created = await githubRequest(`/repos/${owner}/${repo}/git/refs`, {
      method: "POST", body: JSON.stringify({ ref: `refs/heads/${branch}`, sha: base.object.sha })
    });
    await audit(agent.id, "mcp.branch.create", `${owner}/${repo}/${branch}`, "success", requestId, { baseSha: base.object.sha });
    return { branch, base: "main", commitSha: created.object.sha };
  }
  if (name === "yara_write_file") {
    validateRepo(owner, repo, args.path);
    assertAgentBranch(agent.id, args.branch);
    assertWritablePath(args.path);
    if (typeof args.content !== "string" || Buffer.byteLength(args.content, "utf8") > 500000 ||
        typeof args.message !== "string" || args.message.trim().length < 1 || args.message.length > 200) {
      throw new Error("invalid_write_payload");
    }
    const payload = { message: args.message, content: Buffer.from(args.content, "utf8").toString("base64"), branch: args.branch };
    if (typeof args.sha === "string" && /^[a-f0-9]{40}$/.test(args.sha)) payload.sha = args.sha;
    const data = await githubRequest(`/repos/${owner}/${repo}/contents/${args.path.split("/").map(encodeURIComponent).join("/")}`, {
      method: "PUT", body: JSON.stringify(payload)
    });
    await audit(agent.id, "mcp.file.write", `${owner}/${repo}/${args.path}`, "success", requestId, { branch: args.branch, commitSha: data.commit?.sha });
    return { branch: args.branch, commitSha: data.commit?.sha, contentSha: data.content?.sha };
  }
  if (name === "yara_create_draft_pr") {
    if (typeof args.title !== "string" || args.title.trim().length < 1 || args.title.length > 200 ||
        typeof (args.body ?? "") !== "string" || (args.body ?? "").length > 10000) throw new Error("invalid_pull_request_payload");
    assertAgentBranch(agent.id, args.head);
    const data = await githubRequest(`/repos/${owner}/${repo}/pulls`, {
      method: "POST",
      body: JSON.stringify({ title: args.title, body: args.body ?? "", head: args.head, base: "main", draft: true })
    });
    await audit(agent.id, "mcp.pull_request.create", data.html_url, "success", requestId, { number: data.number, head: args.head });
    return { number: data.number, url: data.html_url, draft: data.draft };
  }
  throw new Error("unknown_tool");
}


const mcpHandler = createMcpHandler((ctx) => {
  const auth = ctx.authInfo ?? {};
  const agentId = auth.clientId;
  if (!validAgentId(agentId)) throw new Error("unauthorized");
  const agent = { id: agentId, display_name: auth.displayName ?? agentId };
  const server = new McpServer({ name: "yara-universal-gateway", version: "0.2.0" });

  const register = (name, options) => server.registerTool(name, options, async (args) => {
    try {
      const result = await callMcpTool(agent, name, args, auth.requestId ?? null);
      return { content: [{ type: "text", text: JSON.stringify(result) }] };
    } catch (error) {
      await audit(agent.id, "mcp.tool.call", name, "error", auth.requestId ?? null, { code: error.message }).catch(() => {});
      return { content: [{ type: "text", text: error.message }], isError: true };
    }
  });

  register("yara_task_create", {
    description: "Create a durable task for the YARA agent pool or assign it to one active agent.",
    annotations: { readOnlyHint: false, destructiveHint: false },
    inputSchema: z.object({
      title: z.string().min(3).max(200),
      description: z.string().min(1).max(20000),
      assignedTo: z.string().regex(/^[a-z][a-z0-9-]{1,47}$/).nullable().optional(),
      maxAttempts: z.number().int().min(1).max(10).optional()
    }).strict()
  });
  register("yara_task_claim", {
    description: "Claim the next queued task for this authenticated agent. A lease is created and must be heartbeated through REST if work is long-running.",
    annotations: { readOnlyHint: false, destructiveHint: false },
    inputSchema: z.object({}).strict()
  });
  register("yara_tasks_list", {
    description: "List tasks created by, assigned to, or available to this authenticated agent.",
    annotations: { readOnlyHint: true },
    inputSchema: z.object({ status: z.enum(["queued","running","completed","failed","cancelled"]).optional() }).strict()
  });
  register("yara_task_heartbeat", {
    description: "Extend the three-minute lease for a running task assigned to this agent.",
    annotations: { readOnlyHint: false, destructiveHint: false },
    inputSchema: z.object({ taskId: z.string().uuid() }).strict()
  });
  register("yara_task_complete", {
    description: "Submit a result or failure for a task assigned to this authenticated agent.",
    annotations: { readOnlyHint: false, destructiveHint: false },
    inputSchema: z.object({
      taskId: z.string().uuid(),
      outcome: z.enum(["completed", "failed"]).optional(),
      result: z.unknown().optional()
    }).strict()
  });
  register("yara_message_send", {
    description: "Send a durable direct message to another YARA agent or broadcast to the agent pool.",
    annotations: { readOnlyHint: false, destructiveHint: false },
    inputSchema: z.object({
      recipientId: z.string().regex(/^[a-z][a-z0-9-]{1,47}$/).nullable().optional(),
      kind: z.enum(["message", "task_update", "result", "request"]).optional(),
      body: z.unknown(),
      idempotencyKey: z.string().max(128).optional()
    }).strict()
  });
  register("yara_message_inbox", {
    description: "Read durable direct and broadcast messages for this authenticated agent.",
    annotations: { readOnlyHint: true },
    inputSchema: z.object({ since: z.string().datetime().optional() }).strict()
  });
  register("yara_message_ack", {
    description: "Acknowledge a direct or broadcast message delivered to this agent.",
    annotations: { readOnlyHint: false, destructiveHint: false },
    inputSchema: z.object({ messageId: z.string().uuid() }).strict()
  });
  register("yara_identity", {
    description: "Return the authenticated YARA agent identity.",
    annotations: { readOnlyHint: true },
    inputSchema: z.object({}).strict()
  });
  register("yara_read_file", {
    description: "Read a file from the configured YARA repository.",
    annotations: { readOnlyHint: true },
    inputSchema: z.object({
      owner: z.string(),
      repo: z.string(),
      path: z.string().min(1).max(400)
    }).strict()
  });
  register("yara_create_branch", {
    description: "Create a branch under the authenticated agent namespace, based on main.",
    annotations: { readOnlyHint: false, destructiveHint: false },
    inputSchema: z.object({
      owner: z.string(),
      repo: z.string(),
      name: z.string().regex(/^[a-z0-9][a-z0-9-]{1,79}$/)
    }).strict()
  });
  register("yara_write_file", {
    description: "Create or update a file only on the authenticated agent's own branch.",
    annotations: { readOnlyHint: false, destructiveHint: false },
    inputSchema: z.object({
      owner: z.string(),
      repo: z.string(),
      path: z.string().min(1).max(400),
      branch: z.string().min(1).max(200),
      content: z.string().max(500000),
      message: z.string().min(1).max(200),
      sha: z.string().regex(/^[a-f0-9]{40}$/).optional()
    }).strict()
  });
  register("yara_create_draft_pr", {
    description: "Create a draft pull request from the authenticated agent's branch to main. Does not merge.",
    annotations: { readOnlyHint: false, destructiveHint: false },
    inputSchema: z.object({
      owner: z.string(),
      repo: z.string(),
      head: z.string().min(1).max(200),
      title: z.string().min(1).max(200),
      body: z.string().max(10000).optional()
    }).strict()
  });

  return server;
}, {
  legacy: "stateless",
  responseMode: "auto",
  maxRequestBodySize: 1024 * 1024
});
const mcpNodeHandler = toNodeHandler(mcpHandler, { maxRequestBodySize: 1024 * 1024 });

app.all("/mcp", { preHandler: authenticate }, async (request, reply) => {
  const token = parseBearer(request.headers.authorization);
  request.raw.auth = {
    token: hashToken(token),
    clientId: request.agent.id,
    scopes: ["project:read", "branch:write", "pull_request:create"],
    displayName: request.agent.display_name,
    requestId: request.id
  };
  reply.hijack();
  await mcpNodeHandler(request.raw, reply.raw, request.body);
});

app.get("/healthz", async (_request, reply) => {
  await pool.query("SELECT 1");
  return reply.send({ status: "ok", service: "yara-universal-gateway" });
});

app.post("/v1/admin/agents", async (request, reply) => {
  const supplied = parseBearer(request.headers.authorization);
  if (!supplied || !safeEqual(supplied, env.YARA_BOOTSTRAP_TOKEN)) {
    await audit(null, "agent.provision", "agent-registry", "denied", request.id);
    return reply.code(401).send({ error: "unauthorized" });
  }
  const { id, displayName } = request.body ?? {};
  if (!validAgentId(id) || typeof displayName !== "string" || displayName.trim().length < 1 || displayName.length > 100) {
    return reply.code(400).send({ error: "invalid_agent" });
  }
  const token = issueAgentToken();
  try {
    await pool.query(
      "INSERT INTO yara_agents(id, display_name, token_hash) VALUES($1,$2,$3)",
      [id, displayName.trim(), hashToken(token)]
    );
  } catch (error) {
    if (error.code === "23505") return reply.code(409).send({ error: "agent_id_already_exists" });
    throw error;
  }
  await audit("owner", "agent.provision", id, "success", request.id, { capabilities: ["project:read", "branch:write", "pull_request:create"] });
  return reply.code(201).send({ id, token, warning: "Store this token securely. It will not be shown again." });
});

app.post("/v1/admin/agents/:id/rotate", async (request, reply) => {
  const supplied = parseBearer(request.headers.authorization);
  if (!supplied || !safeEqual(supplied, env.YARA_BOOTSTRAP_TOKEN)) {
    return reply.code(401).send({ error: "unauthorized" });
  }
  const token = issueAgentToken();
  const result = await pool.query(
    "UPDATE yara_agents SET token_hash=$2, active=true, revoked_at=NULL WHERE id=$1 RETURNING id",
    [request.params.id, hashToken(token)]
  );
  await audit("owner", "agent.token.rotate", request.params.id, result.rowCount ? "success" : "not_found", request.id);
  return result.rowCount
    ? reply.send({ id: request.params.id, token, warning: "Store this token securely. It will not be shown again." })
    : reply.code(404).send({ error: "agent_not_found" });
});

app.post("/v1/admin/agents/:id/revoke", async (request, reply) => {
  const supplied = parseBearer(request.headers.authorization);
  if (!supplied || !safeEqual(supplied, env.YARA_BOOTSTRAP_TOKEN)) {
    return reply.code(401).send({ error: "unauthorized" });
  }
  const result = await pool.query(
    "UPDATE yara_agents SET active=false, revoked_at=now() WHERE id=$1 AND active=true RETURNING id",
    [request.params.id]
  );
  await audit("owner", "agent.revoke", request.params.id, result.rowCount ? "success" : "not_found", request.id);
  return result.rowCount ? reply.send({ id: request.params.id, revoked: true }) : reply.code(404).send({ error: "agent_not_found" });
});

app.post("/v1/projects/:owner/:repo/branches", { preHandler: authenticate }, async (request, reply) => {
  const { owner, repo } = request.params;
  if (`${owner}/${repo}` !== env.GITHUB_REPOSITORY) return reply.code(403).send({ error: "repository_not_allowed" });
  const { name } = request.body ?? {};
  if (!validRepoPart(owner) || !validRepoPart(repo) || typeof name !== "string" ||
      !/^[a-z0-9][a-z0-9-]{1,79}$/.test(name)) {
    return reply.code(400).send({ error: "invalid_branch_name" });
  }
  const branch = `agents/${request.agent.id}/${name}`;
  try {
    assertAgentBranch(request.agent.id, branch);
  } catch (error) {
    return reply.code(403).send({ error: error.message });
  }
  const base = await githubRequest(`/repos/${owner}/${repo}/git/ref/heads/main`);
  const created = await githubRequest(`/repos/${owner}/${repo}/git/refs`, {
    method: "POST",
    body: JSON.stringify({ ref: `refs/heads/${branch}`, sha: base.object.sha })
  });
  await audit(request.agent.id, "branch.create", `${owner}/${repo}/${branch}`, "success", request.id, {
    base: "main", baseSha: base.object.sha, commitSha: created.object.sha
  });
  return reply.code(201).send({ branch, base: "main", commitSha: created.object.sha });
});

app.get("/v1/agents/me", { preHandler: authenticate }, async (request) => {
  await audit(request.agent.id, "agent.identity.read", request.agent.id, "success", request.id);
  return { id: request.agent.id, displayName: request.agent.display_name, status: "active" };
});

app.get("/v1/projects/:owner/:repo/files", { preHandler: authenticate }, async (request, reply) => {
  const { owner, repo } = request.params;
  const path = request.query.path;
  validateRepo(owner, repo, path);
  const data = await githubRequest(`/repos/${owner}/${repo}/contents/${path.split("/").map(encodeURIComponent).join("/")}`);
  if (Array.isArray(data) || !data.content) return reply.code(404).send({ error: "file_not_found" });
  await audit(request.agent.id, "project.file.read", `${owner}/${repo}/${path}`, "success", request.id, { sha: data.sha });
  return { path, sha: data.sha, encoding: data.encoding, content: Buffer.from(data.content, "base64").toString("utf8") };
});

app.put("/v1/projects/:owner/:repo/files", { preHandler: authenticate }, async (request, reply) => {
  const { owner, repo } = request.params;
  const { path, branch, content, message, sha } = request.body ?? {};
  validateRepo(owner, repo, path);
  try {
    assertAgentBranch(request.agent.id, branch);
  } catch (error) {
    await audit(request.agent.id, "project.file.write", `${owner}/${repo}/${path}`, "denied", request.id, { reason: error.message });
    return reply.code(403).send({ error: error.message });
  }
  try {
    assertWritablePath(path);
  } catch (error) {
    await audit(request.agent.id, "project.file.write", `${owner}/${repo}/${path}`, "denied", request.id, { reason: error.message });
    return reply.code(403).send({ error: error.message });
  }
  if (typeof content !== "string" || Buffer.byteLength(content, "utf8") > 500000 ||
      typeof message !== "string" || message.trim().length < 1 || message.length > 200) {
    return reply.code(400).send({ error: "invalid_write_payload" });
  }
  const payload = { message, content: Buffer.from(content, "utf8").toString("base64"), branch };
  if (typeof sha === "string" && /^[a-f0-9]{40}$/.test(sha)) payload.sha = sha;
  const data = await githubRequest(
    `/repos/${owner}/${repo}/contents/${path.split("/").map(encodeURIComponent).join("/")}`,
    { method: "PUT", body: JSON.stringify(payload) }
  );
  await audit(request.agent.id, "project.file.write", `${owner}/${repo}/${path}`, "success", request.id, {
    branch, commitSha: data.commit?.sha, contentSha: data.content?.sha
  });
  return reply.code(201).send({ path, branch, commitSha: data.commit?.sha, contentSha: data.content?.sha });
});

app.post("/v1/projects/:owner/:repo/pull-requests", { preHandler: authenticate }, async (request, reply) => {
  const { owner, repo } = request.params;
  if (`${owner}/${repo}` !== env.GITHUB_REPOSITORY) return reply.code(403).send({ error: "repository_not_allowed" });
  const { head, title, body = "", base = "main" } = request.body ?? {};
  if (!validRepoPart(owner) || !validRepoPart(repo) || typeof title !== "string" || title.trim().length < 1 || title.length > 200 ||
      typeof body !== "string" || body.length > 10000 || base !== "main") {
    return reply.code(400).send({ error: "invalid_pull_request_payload" });
  }
  try {
    assertAgentBranch(request.agent.id, head);
  } catch (error) {
    await audit(request.agent.id, "pull_request.create", `${owner}/${repo}`, "denied", request.id, { reason: error.message });
    return reply.code(403).send({ error: error.message });
  }
  const data = await githubRequest(`/repos/${owner}/${repo}/pulls`, {
    method: "POST",
    body: JSON.stringify({ title, body, head, base, draft: true })
  });
  await audit(request.agent.id, "pull_request.create", data.html_url, "success", request.id, { number: data.number, head });
  return reply.code(201).send({ number: data.number, url: data.html_url, draft: data.draft, state: data.state });
});

app.setErrorHandler(async (error, request, reply) => {
  request.log.error({ err: error, requestId: request.id }, "request_failed");
  const status = Number.isInteger(error.statusCode) ? error.statusCode : 500;
  await audit(request.agent?.id ?? null, "request.error", request.url, "error", request.id, { code: error.message }).catch(() => {});
  return reply.code(status).send({ error: status >= 500 ? "internal_error" : error.message });
});

await migrate();
await migrateCollaboration(pool);
registerCollaborationRoutes(app, { pool, authenticate, audit });
const port = Number(env.PORT ?? 8080);
await app.listen({ host: env.HOST ?? "0.0.0.0", port });

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, async () => {
    await app.close();
    await pool.end();
    process.exit(0);
  });
}
