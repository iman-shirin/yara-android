import test from "node:test";
import assert from "node:assert/strict";
import { assertAgentBranch, assertWritablePath, hashToken, issueAgentToken, safeEqual, validAgentId, validRepoPath } from "../src/security.mjs";

test("agent tokens are random and stored as one-way hashes", () => {
  const a = issueAgentToken();
  const b = issueAgentToken();
  assert.notEqual(a, b);
  assert.equal(hashToken(a).length, 64);
  assert.notEqual(hashToken(a), a);
});

test("constant-time comparison rejects unequal values", () => {
  assert.equal(safeEqual("same", "same"), true);
  assert.equal(safeEqual("same", "different"), false);
});

test("agent identifiers are constrained", () => {
  assert.equal(validAgentId("agent-claude"), true);
  assert.equal(validAgentId("Agent_Claude"), false);
  assert.equal(validAgentId("../admin"), false);
});

test("repository paths reject traversal and absolute paths", () => {
  assert.equal(validRepoPath("docs/architecture.md"), true);
  assert.equal(validRepoPath("../secrets"), false);
  assert.equal(validRepoPath("/etc/passwd"), false);
  assert.equal(validRepoPath("a//b"), false);
});

test("agents can only write under their own branch namespace", () => {
  assert.equal(assertAgentBranch("agent-claude", "agents/agent-claude/task-123"), true);
  assert.throws(() => assertAgentBranch("agent-claude", "main"), /protected_branch/);
  assert.throws(() => assertAgentBranch("agent-claude", "agents/agent-gemini/task-123"), /branch_out_of_scope/);
  assert.throws(() => assertAgentBranch("agent-claude", "feature/task-123"), /branch_out_of_scope/);
});

test("agent write policy blocks workflow, gateway security and secret paths", () => {
  assert.equal(assertWritablePath("app/src/main/java/example/Feature.kt"), true);
  assert.equal(assertWritablePath("docs/architecture.md"), true);
  assert.throws(() => assertWritablePath(".github/workflows/deploy.yml"), /protected_path/);
  assert.throws(() => assertWritablePath("gateway/src/server.mjs"), /protected_path/);
  assert.throws(() => assertWritablePath("gateway/.env.example"), /protected_path/);
  assert.throws(() => assertWritablePath("app/local.properties"), /protected_path/);
  assert.throws(() => assertWritablePath("secrets/provider.json"), /protected_path/);
  assert.throws(() => assertWritablePath("gateway/config/.env.production"), /protected_path/);
  assert.throws(() => assertWritablePath(".devcontainer/setup.sh"), /protected_path/);
  assert.throws(() => assertWritablePath(".github/actions/custom/action.yml"), /protected_path/);
  assert.throws(() => assertWritablePath(".railway/railway.ts"), /protected_path/);
  assert.throws(() => assertWritablePath("AGENTS.md"), /protected_path/);
  assert.throws(() => assertWritablePath("gateway/scripts/smoke-mcp.sh"), /protected_path/);
});
