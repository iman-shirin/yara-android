import test from "node:test";
import assert from "node:assert/strict";
import { validateTaskPayload, validateMessagePayload } from "../src/collaboration.mjs";

test("task payload accepts bounded valid tasks", () => {
  const result = validateTaskPayload({ title: "Review architecture", description: "Inspect the task bus." });
  assert.equal(result.ok, true);
  assert.equal(result.value.maxAttempts, 3);
});

test("task payload rejects malformed titles, oversized descriptions and unsafe assignees", () => {
  assert.equal(validateTaskPayload({ title: "x", description: "ok" }).error, "invalid_title");
  assert.equal(validateTaskPayload({ title: "Valid", description: "x".repeat(20001) }).error, "invalid_description");
  assert.equal(validateTaskPayload({ title: "Valid", description: "ok", assignedTo: "../owner" }).error, "invalid_assignee");
  assert.equal(validateTaskPayload({ title: "Valid", description: "ok", maxAttempts: 11 }).error, "invalid_max_attempts");
});

test("message payload validates recipients, kinds and bounded body", () => {
  assert.equal(validateMessagePayload({ recipientId: "agent-claude", body: { text: "hello" } }).ok, true);
  assert.equal(validateMessagePayload({ recipientId: "../admin", body: "hello" }).error, "invalid_recipient");
  assert.equal(validateMessagePayload({ taskId: "not-a-uuid", body: "hello" }).error, "invalid_task_id");
  assert.equal(validateMessagePayload({ kind: "execute-shell", body: "x" }).error, "invalid_message_kind");
  assert.equal(validateMessagePayload({ idempotencyKey: 7, body: "x" }).error, "invalid_idempotency_key");
  assert.equal(validateMessagePayload({ body: "x".repeat(50001) }).error, "invalid_message_body");
});
