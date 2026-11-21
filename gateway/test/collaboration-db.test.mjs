import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import pg from "pg";
import Fastify from "fastify";
import { migrateCollaboration, registerCollaborationRoutes } from "../src/collaboration.mjs";

const { Pool } = pg;
const databaseUrl = process.env.TEST_DATABASE_URL;

if (!databaseUrl) {
  test("PostgreSQL collaboration integration (set TEST_DATABASE_URL)", { skip: true }, () => {});
} else {
  let pool;
  let app;

  before(async () => {
    pool = new Pool({ connectionString: databaseUrl });
    await pool.query(`
      CREATE TABLE IF NOT EXISTS yara_agents (
        id text PRIMARY KEY,
        display_name text NOT NULL,
        token_hash text NOT NULL UNIQUE,
        active boolean NOT NULL DEFAULT true,
        created_at timestamptz NOT NULL DEFAULT now(),
        revoked_at timestamptz
      )
    `);
    await migrateCollaboration(pool);
    await pool.query("DELETE FROM yara_messages");
    await pool.query("DELETE FROM yara_tasks");
    await pool.query("DELETE FROM yara_agents WHERE id IN ('agent-test-a','agent-test-b','agent-test-c')");
    await pool.query(
      "INSERT INTO yara_agents(id,display_name,token_hash) VALUES ('agent-test-a','Test A','hash-a'),('agent-test-b','Test B','hash-b'),('agent-test-c','Test C','hash-c')"
    );
    app = Fastify({ logger: false });
    const authenticate = async (request, reply) => {
      const id = request.headers["x-agent-id"];
      if (!["agent-test-a", "agent-test-b", "agent-test-c"].includes(id)) return reply.code(401).send({ error: "unauthorized" });
      request.agent = { id, display_name: id };
    };
    registerCollaborationRoutes(app, { pool, authenticate, audit: async () => {} });
    await app.ready();
  });

  after(async () => {
    await app?.close();
    if (pool) {
      await pool.query("DELETE FROM yara_messages");
      await pool.query("DELETE FROM yara_tasks");
      await pool.query("DELETE FROM yara_agents WHERE id IN ('agent-test-a','agent-test-b')");
      await pool.end();
    }
  });

  const call = (method, url, agent, payload) => app.inject({
    method, url, headers: { "x-agent-id": agent }, ...(payload === undefined ? {} : { payload })
  });

  test("tasks are claimed once, heartbeated, completed, retried after lease expiry, and reported", async () => {
    const created = await call("POST", "/v1/tasks", "agent-test-a", {
      title: "Integration task", description: "Verify durable queue behavior.", assignedTo: "agent-test-b", maxAttempts: 3
    });
    assert.equal(created.statusCode, 201);
    const taskId = created.json().task.id;

    const claimed = await call("POST", "/v1/tasks/claim", "agent-test-b");
    assert.equal(claimed.statusCode, 200);
    assert.equal(claimed.json().task.id, taskId);
    assert.equal(claimed.json().task.attempts, 1);

    const heartbeat = await call("POST", `/v1/tasks/${taskId}/heartbeat`, "agent-test-b", {});
    assert.equal(heartbeat.statusCode, 200);

    const completed = await call("POST", `/v1/tasks/${taskId}/complete`, "agent-test-b", {
      outcome: "completed", result: { summary: "All checks passed." }
    });
    assert.equal(completed.statusCode, 200);
    assert.equal(completed.json().task.status, "completed");

    const creatorMessages = await call("GET", "/v1/messages/inbox", "agent-test-a");
    const report = creatorMessages.json().messages.find((message) => message.task_id === taskId);
    assert.ok(report);
    assert.equal(report.kind, "result");
    const ack = await call("POST", `/v1/messages/${report.id}/ack`, "agent-test-a", {});
    assert.equal(ack.statusCode, 200);

    const retryTask = await call("POST", "/v1/tasks", "agent-test-a", {
      title: "Retry integration", description: "Verify abandoned task recovery.", maxAttempts: 3
    });
    const retryId = retryTask.json().task.id;
    const firstClaim = await call("POST", "/v1/tasks/claim", "agent-test-a");
    assert.equal(firstClaim.json().task.id, retryId);
    await pool.query("UPDATE yara_tasks SET lease_until=now()-interval '1 second' WHERE id=$1", [retryId]);

    const secondClaim = await call("POST", "/v1/tasks/claim", "agent-test-b");
    assert.equal(secondClaim.json().task.id, retryId);
    assert.equal(secondClaim.json().task.attempts, 2);
    const failed = await call("POST", `/v1/tasks/${retryId}/complete`, "agent-test-b", {
      outcome: "failed", result: { error: "transient failure" }
    });
    assert.equal(failed.statusCode, 200);
    assert.equal(failed.json().retryScheduled, true);
    assert.equal(failed.json().task.status, "queued");
    assert.equal(failed.json().task.assigned_to, null);

    const finalClaim = await call("POST", "/v1/tasks/claim", "agent-test-a");
    assert.equal(finalClaim.json().task.id, retryId);
    const exhausted = await call("POST", `/v1/tasks/${retryId}/complete`, "agent-test-a", {
      outcome: "failed", result: { error: "attempt budget exhausted" }
    });
    assert.equal(exhausted.json().task.status, "failed");
    assert.equal(exhausted.json().retryScheduled, false);

    const concurrentTask = await call("POST", "/v1/tasks", "agent-test-a", {
      title: "Concurrent claim", description: "Only one worker may claim this task."
    });
    const concurrentId = concurrentTask.json().task.id;
    const claims = await Promise.all([
      call("POST", "/v1/tasks/claim", "agent-test-a"),
      call("POST", "/v1/tasks/claim", "agent-test-b")
    ]);
    assert.equal(claims.filter((response) => response.statusCode === 200 && response.json().task?.id === concurrentId).length, 1);
    assert.equal(claims.filter((response) => response.statusCode === 204).length, 1);
  });

  test("direct and broadcast messages are durable and acknowledgeable", async () => {
    const direct = await call("POST", "/v1/messages", "agent-test-a", {
      recipientId: "agent-test-b", kind: "message", body: { text: "Please review this result." }, idempotencyKey: "direct-1"
    });
    assert.equal(direct.statusCode, 201);
    const inbox = await call("GET", "/v1/messages/inbox", "agent-test-b");
    assert.ok(inbox.json().messages.some((message) => message.id === direct.json().message.id));
    const ack = await call("POST", `/v1/messages/${direct.json().message.id}/ack`, "agent-test-b", {});
    assert.equal(ack.statusCode, 200);

    const duplicate = await call("POST", "/v1/messages", "agent-test-a", {
      recipientId: "agent-test-b", body: { text: "duplicate" }, idempotencyKey: "direct-1"
    });
    assert.equal(duplicate.statusCode, 409);

    const broadcast = await call("POST", "/v1/messages", "agent-test-a", {
      kind: "message", body: { text: "Shared update" }
    });
    assert.equal(broadcast.statusCode, 201);
    const broadcastInbox = await call("GET", "/v1/messages/inbox", "agent-test-b");
    assert.ok(broadcastInbox.json().messages.some((message) => message.id === broadcast.json().message.id));
  });

  test("task visibility is restricted to creator, assignee and unassigned queue", async () => {
    const created = await call("POST", "/v1/tasks", "agent-test-a", {
      title: "Private assignment", description: "Only creator and assignee can see this.", assignedTo: "agent-test-b"
    });
    const id = created.json().task.id;
    const messages = await call("GET", `/v1/tasks/${id}/messages`, "agent-test-a");
    assert.equal(messages.statusCode, 200);
    const stranger = await call("GET", `/v1/tasks/${id}/messages`, "agent-test-c");
    assert.equal(stranger.statusCode, 404);

    const sent = await call("POST", "/v1/messages", "agent-test-a", {
      taskId: id, recipientId: "agent-test-b", body: { text: "Task discussion begins." }
    });
    assert.equal(sent.statusCode, 201);
    const reply = await call("POST", "/v1/messages", "agent-test-b", {
      taskId: id, recipientId: "agent-test-a", body: { text: "I received the task context." }
    });
    assert.equal(reply.statusCode, 201);
    const thread = await call("GET", `/v1/tasks/${id}/messages`, "agent-test-a");
    assert.equal(thread.json().messages.length, 2);
    const denied = await call("POST", "/v1/messages", "agent-test-c", {
      taskId: id, recipientId: "agent-test-a", body: { text: "Unauthorized task message." }
    });
    assert.equal(denied.statusCode, 404);
  });
}
