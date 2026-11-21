import { createHash, randomBytes, timingSafeEqual } from "node:crypto";

export function hashToken(token) {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

export function issueAgentToken() {
  return randomBytes(32).toString("base64url");
}

export function safeEqual(left, right) {
  const a = Buffer.from(String(left ?? ""));
  const b = Buffer.from(String(right ?? ""));
  return a.length === b.length && timingSafeEqual(a, b);
}

export function validAgentId(value) {
  return typeof value === "string" && /^[a-z][a-z0-9-]{1,47}$/.test(value);
}

export function validRepoPart(value) {
  return typeof value === "string" && /^[A-Za-z0-9_.-]{1,100}$/.test(value) && value !== "." && value !== "..";
}

export function validRepoPath(value) {
  if (typeof value !== "string" || value.length < 1 || value.length > 400) return false;
  if (value.startsWith("/") || value.includes("\\") || value.includes("\0")) return false;
  const parts = value.split("/");
  return parts.every((part) => part.length > 0 && part !== "." && part !== "..");
}

export function assertAgentBranch(agentId, branch) {
  if (typeof branch !== "string" || branch.length > 200 || branch.startsWith("-")) {
    throw new Error("invalid_branch");
  }
  if (branch === "main" || branch === "master" || branch.startsWith("main/") || branch.startsWith("master/")) {
    throw new Error("protected_branch");
  }
  if (!branch.startsWith(`agents/${agentId}/`) || branch.length <= `agents/${agentId}/`.length) {
    throw new Error("branch_out_of_scope");
  }
  if (branch.split("/").some((part) => !part || part === "." || part === ".." || part.startsWith("."))) {
    throw new Error("invalid_branch");
  }
  return true;
}

export function assertWritablePath(path) {
  if (!validRepoPath(path)) throw new Error("invalid_resource_path");
  const normalized = path.toLowerCase();
  const blocked = [
    ".github/workflows/",
    ".github/codeowners",
    ".github/rulesets/",
    ".github/actions/",
    ".devcontainer/",
    ".railway/",
    "agents.md",
    ".github/copilot-instructions.md",
    ".github/instructions/",
    ".git/",
    ".gitmodules",
    "gateway/src/server.mjs",
    "gateway/src/security.mjs",
    "gateway/scripts/",
    "gateway/package.json",
    "gateway/package-lock.json",
    "gateway/dockerfile",
    "gateway/.env",
    "gateway/.env.example",
    "secrets/",
    ".env"
  ];
  if (blocked.some((prefix) => normalized === prefix || normalized.startsWith(prefix))) {
    throw new Error("protected_path");
  }
  if (normalized.split("/").some((segment) => segment === ".env" || segment.startsWith(".env."))) {
    throw new Error("protected_path");
  }
  if (normalized.endsWith(".pem") || normalized.endsWith(".key") ||
      normalized.endsWith(".p12") || normalized.endsWith(".jks") ||
      normalized.endsWith(".keystore") || normalized.endsWith("local.properties")) {
    throw new Error("protected_path");
  }
  return true;
}
