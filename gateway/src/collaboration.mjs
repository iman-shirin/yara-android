import { randomUUID } from "node:crypto";

const TASK_STATES = new Set(["queued", "running", "completed", "failed", "cancelled"]);
const MESSAGE_KINDS = new Set(["message", "task_update", "result", "request"]);

export function validateTaskPayload(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return { ok: false, error: "invalid_task" };
  if (typeof body.title !== "string" || body.title.trim().length < 3 || body.title.length > 200) return { ok: false, error: "invalid_title" };
  if (typeof body.description !== "string" || body.description.trim().length < 1 || Buffer.byteLength(body.description, "utf8") > 20000) return { ok: false, error: "invalid_description" };
  if (body.assignedTo !== undefined && body.assignedTo !== null &&
      (typeof body.assignedTo !== "string" || !/^[a-z][a-z0-9-]{1,47}$/.test(body.assignedTo))) return { ok: false, error: "invalid_assignee" };
  const maxAttempts = body.maxAttempts === undefined ? 3 : body.maxAttempts;
  if (!Number.isInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > 10) return { ok: false, error: "invalid_max_attempts" };
  return { ok: true, value: { title: body.title.trim(), description: body.description, assignedTo: body.assignedTo ?? null, maxAttempts } };
}

export function validateMessagePayload(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return { ok: false, error: "invalid_message" };
  if (body.recipientId !== null && body.recipientId !== undefined &&
      (typeof body.recipientId !== "string" || !/^[a-z][a-z0-9-]{1,47}$/.test(body.recipientId))) return { ok: false, error: "invalid_recipient" };
  const kind = body.kind ?? "message";
  if (!MESSAGE_KINDS.has(kind)) return { ok: false, error: "invalid_message_kind" };
  if (body.body === undefined || Buffer.byteLength(JSON.stringify(body.body), "utf8") > 50000) return { ok: false, error: "invalid_message_body" };
  return { ok: true, value: { recipientId: body.recipientId ?? null, kind, body: body.body, idempotencyKey: body.idempotencyKey ?? null } };
}

export async function migrateCollaboration(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS yara_tasks (
      id uuid PRIMARY KEY,
      title text NOT NULL,
      description text NOT NULL,
      created_by text NOT NULL REFERENCES yara_agents(id),
      assigned_to text REFERENCES yara_agents(id),
      status text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','running','completed','failed','cancelled')),
      attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
      max_attempts integer NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
      lease_until timestamptz,
      result jsonb,
      created_at timestamptz NOT NULL DEFAULT now(),
      updated_at timestamptz NOT NULL DEFAULT now(),
      completed_at timestamptz
    );
    CREATE INDEX IF NOT EXISTS yara_tasks_queue_idx ON yara_tasks(status, created_at) WHERE status = 'queued';
    CREATE INDEX IF NOT EXISTS yara_tasks_assignee_idx ON yara_tasks(assigned_to, status, updated_at DESC);
    CREATE TABLE IF NOT EXISTS yara_messages (
      id uuid PRIMARY KEY,
      task_id uuid REFERENCES yara_tasks(id) ON DELETE CASCADE,
      sender_id text NOT NULL REFERENCES yara_agents(id),
      recipient_id text REFERENCES yara_agents(id),
      kind text NOT NULL CHECK (kind IN ('message','task_update','result','request')),
      body jsonb NOT NULL,
      idempotency_key text,
      created_at timestamptz NOT NULL DEFAULT now(),
      read_at timestamptz,
      UNIQUE(sender_id, idempotency_key)
    );
    CREATE INDEX IF NOT EXISTS yara_messages_inbox_idx ON yara_messages(recipient_id, created_at) WHERE read_at IS NULL;
    CREATE INDEX IF NOT EXISTS yara_messages_task_idx ON yara_messages(task_id, created_at);
  `);
}

export function registerCollaborationRoutes(app, { pool, authenticate, audit }) {
  app.post("/v1/tasks", { preHandler: authenticate }, async (request, reply) => {
    const parsed = validateTaskPayload(request.body);
    if (!parsed.ok) return reply.code(400).send({ error: parsed.error });
    const { title, description, assignedTo, maxAttempts } = parsed.value;
    if (assignedTo) {
      const exists = await pool.query("SELECT id FROM yara_agents WHERE id=$1 AND active=true", [assignedTo]);
      if (!exists.rowCount) return reply.code(404).send({ error: "assignee_not_found" });
    }
    const id = randomUUID();
    const result = await pool.query(
      "INSERT INTO yara_tasks(id,title,description,created_by,assigned_to,max_attempts) VALUES($1,$2,$3,$4,$5,$6) RETURNING *",
      [id, title, description, request.agent.id, assignedTo, maxAttempts]
    );
    await audit(request.agent.id, "task.create", id, "success", request.id, { assignedTo });
    return reply.code(201).send({ task: result.rows[0] });
  });

  app.get("/v1/tasks", { preHandler: authenticate }, async (request) => {
    const state = request.query.status;
    if (state && !TASK_STATES.has(state)) return { error: "invalid_status" };
    const values = [request.agent.id];
    let filter = "(created_by=$1 OR assigned_to=$1 OR (assigned_to IS NULL AND status='queued'))";
    if (state) { values.push(state); filter += " AND status=$2"; }
    const result = await pool.query(
      `SELECT id,title,description,created_by,assigned_to,status,attempts,max_attempts,lease_until,result,created_at,updated_at,completed_at FROM yara_tasks WHERE ${filter} ORDER BY created_at DESC LIMIT 100`,
      values
    );
    return { tasks: result.rows };
  });

  app.post("/v1/tasks/claim", { preHandler: authenticate }, async (request, reply) => {
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      await client.query("UPDATE yara_tasks SET status='failed',lease_until=NULL,completed_at=now(),updated_at=now() WHERE status='running' AND lease_until < now() AND attempts >= max_attempts");
      await client.query("UPDATE yara_tasks SET status='queued',assigned_to=NULL,lease_until=NULL,updated_at=now() WHERE status='running' AND lease_until < now() AND attempts < max_attempts");
      const selected = await client.query(
        "SELECT id FROM yara_tasks WHERE status='queued' AND (assigned_to IS NULL OR assigned_to=$1) AND attempts < max_attempts ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
        [request.agent.id]
      );
      if (!selected.rowCount) { await client.query("COMMIT"); return reply.code(204).send(); }
      const updated = await client.query(
        "UPDATE yara_tasks SET status='running',assigned_to=$2,attempts=attempts+1,lease_until=now()+interval '3 minutes',updated_at=now() WHERE id=$1 RETURNING *",
        [selected.rows[0].id, request.agent.id]
      );
      await client.query("COMMIT");
      await audit(request.agent.id, "task.claim", updated.rows[0].id, "success", request.id, { attempt: updated.rows[0].attempts });
      return { task: updated.rows[0] };
    } catch (error) {
      await client.query("ROLLBACK").catch(() => {});
      throw error;
    } finally { client.release(); }
  });

  app.post("/v1/tasks/:id/heartbeat", { preHandler: authenticate }, async (request, reply) => {
    const result = await pool.query(
      "UPDATE yara_tasks SET lease_until=now()+interval '3 minutes',updated_at=now() WHERE id=$1 AND assigned_to=$2 AND status='running' RETURNING id,lease_until",
      [request.params.id, request.agent.id]
    );
    if (!result.rowCount) return reply.code(404).send({ error: "active_task_not_found" });
    return result.rows[0];
  });

  app.post("/v1/tasks/:id/complete", { preHandler: authenticate }, async (request, reply) => {
    const outcome = request.body?.outcome ?? "completed";
    if (!["completed", "failed"].includes(outcome)) return reply.code(400).send({ error: "invalid_outcome" });
    const resultBody = request.body?.result ?? {};
    if (Buffer.byteLength(JSON.stringify(resultBody), "utf8") > 50000) return reply.code(400).send({ error: "result_too_large" });
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      const task = await client.query("SELECT * FROM yara_tasks WHERE id=$1 FOR UPDATE", [request.params.id]);
      if (!task.rowCount || task.rows[0].assigned_to !== request.agent.id || task.rows[0].status !== "running") {
        await client.query("ROLLBACK");
        return reply.code(404).send({ error: "active_task_not_found" });
      }
      const retry = outcome === "failed" && task.rows[0].attempts < task.rows[0].max_attempts;
      const status = outcome === "completed" ? "completed" : retry ? "queued" : "failed";
      const updated = await client.query(
        "UPDATE yara_tasks SET status=$2,assigned_to=CASE WHEN $2='queued' THEN NULL ELSE assigned_to END,result=$3,lease_until=NULL,updated_at=now(),completed_at=CASE WHEN $2 IN ('completed','failed') THEN now() ELSE NULL END WHERE id=$1 RETURNING *",
        [request.params.id, status, JSON.stringify(resultBody)]
      );
      await client.query(
        "INSERT INTO yara_messages(id,task_id,sender_id,recipient_id,kind,body) VALUES($1,$2,$3,$4,'result',$5)",
        [randomUUID(), request.params.id, request.agent.id, task.rows[0].created_by, JSON.stringify({ outcome, retryScheduled: retry, result: resultBody })]
      );
      await client.query("COMMIT");
      await audit(request.agent.id, "task.complete", request.params.id, "success", request.id, { status });
      return { task: updated.rows[0], retryScheduled: retry };
    } catch (error) {
      await client.query("ROLLBACK").catch(() => {});
      throw error;
    } finally { client.release(); }
  });

  app.post("/v1/messages", { preHandler: authenticate }, async (request, reply) => {
    const parsed = validateMessagePayload(request.body);
    if (!parsed.ok) return reply.code(400).send({ error: parsed.error });
    const { recipientId, kind, body, idempotencyKey } = parsed.value;
    if (recipientId) {
      const exists = await pool.query("SELECT id FROM yara_agents WHERE id=$1 AND active=true", [recipientId]);
      if (!exists.rowCount) return reply.code(404).send({ error: "recipient_not_found" });
    }
    if (idempotencyKey && (typeof idempotencyKey !== "string" || idempotencyKey.length > 128)) return reply.code(400).send({ error: "invalid_idempotency_key" });
    const id = randomUUID();
    try {
      const inserted = await pool.query(
        "INSERT INTO yara_messages(id,sender_id,recipient_id,kind,body,idempotency_key) VALUES($1,$2,$3,$4,$5,$6) RETURNING *",
        [id, request.agent.id, recipientId, kind, JSON.stringify(body), idempotencyKey]
      );
      await audit(request.agent.id, "message.send", recipientId ?? "broadcast", "success", request.id, { messageId: id });
      return reply.code(201).send({ message: inserted.rows[0] });
    } catch (error) {
      if (error.code === "23505" && idempotencyKey) return reply.code(409).send({ error: "duplicate_message" });
      throw error;
    }
  });

  app.get("/v1/messages/inbox", { preHandler: authenticate }, async (request) => {
    const since = request.query.since;
    const values = [request.agent.id];
    let filter = "(recipient_id=$1 OR recipient_id IS NULL)";
    if (since) {
      const timestamp = new Date(since);
      if (Number.isNaN(timestamp.getTime())) return { error: "invalid_since" };
      values.push(timestamp.toISOString());
      filter += " AND created_at > $2";
    }
    const result = await pool.query(
      `SELECT id,task_id,sender_id,recipient_id,kind,body,created_at,read_at FROM yara_messages WHERE ${filter} ORDER BY created_at ASC LIMIT 100`,
      values
    );
    return { messages: result.rows };
  });

  app.post("/v1/messages/:id/ack", { preHandler: authenticate }, async (request, reply) => {
    const result = await pool.query(
      "UPDATE yara_messages SET read_at=now() WHERE id=$1 AND (recipient_id=$2 OR recipient_id IS NULL) RETURNING id,read_at",
      [request.params.id, request.agent.id]
    );
    if (!result.rowCount) return reply.code(404).send({ error: "message_not_found" });
    return result.rows[0];
  });

  app.get("/v1/tasks/:id/messages", { preHandler: authenticate }, async (request, reply) => {
    const access = await pool.query("SELECT created_by,assigned_to FROM yara_tasks WHERE id=$1", [request.params.id]);
    if (!access.rowCount || ![access.rows[0].created_by, access.rows[0].assigned_to].includes(request.agent.id)) {
      return reply.code(404).send({ error: "task_not_found" });
    }
    const result = await pool.query("SELECT id,sender_id,recipient_id,kind,body,created_at FROM yara_messages WHERE task_id=$1 ORDER BY created_at ASC LIMIT 200", [request.params.id]);
    return { messages: result.rows };
  });
}
