# YARA Cloudflare Free Runtime Migration

## Decision
Replace the Node.js/Fastify/PostgreSQL hosting target with a Cloudflare Workers Fetch runtime and D1 SQLite database. Preserve REST routes, MCP tool names and schemas, per-agent credentials, GitHub App integration, branch isolation, task leases, retries, message inbox, and append-only audit semantics.

**No deployment is part of this migration.** The account plan, billing controls, and deployment permissions must be verified before any production resource is created.

## Why the existing service cannot be deployed unchanged
The current Gateway starts a long-lived Fastify listener, creates a pg.Pool, uses PostgreSQL SQL and transaction APIs, and adapts MCP to Node HTTP. Those are not a direct Workers entry point. The Worker must export a Fetch handler, use D1 bindings, use Web Crypto, and use the MCP SDK's Web-standards stateless handler.

## Target components
- Workers Free: HTTP REST API and stateless MCP Streamable HTTP endpoint.
- D1 Free: agents, tasks, messages and append-only audit events.
- GitHub App: repository operations via short-lived installation tokens; optional until an App is installed.
- Durable Objects are deliberately not required for the first migration. D1's single-statement conditional UPDATE ... RETURNING can claim a task atomically, and D1 batch transactions cover multi-statement completion. Adding a DO now would add a second coordination path and consume another quota without a demonstrated requirement.

## Free-plan guardrails
The published Workers Free limits currently include 100,000 requests/day, 10 ms CPU per request, 128 MB memory and 50 subrequests/request. D1 Free includes 5 GB total account storage, 500 MB per database, 5 million rows read/day and 100,000 rows written/day. Exceeding free quotas must fail closed; do not enable paid upgrades or paid overages.
These are platform-published limits, not confirmation of the user's account settings. Before deployment, verify the account is on Workers Free, confirm no paid plan or automatic upgrade is enabled, and configure a spending limit/usage alert where available. If the account cannot guarantee zero charges, do not deploy.

## Data model and correctness
- yara_agents: unique agent IDs, display names, SHA-256 token hashes, active/revoked state.
- yara_tasks: task state, attempts, max attempts, assignee, lease expiry, result and timestamps.
- yara_messages: direct/broadcast messages, task association, idempotency key and acknowledgement time.
- yara_audit: append-only action history, protected by SQLite triggers against UPDATE/DELETE.
Task claiming must be a single conditional SQL statement using a subquery and RETURNING, so two concurrent Workers cannot claim the same queued row. Expired leases are recovered in the same atomic claim operation: exhausted tasks become failed; retryable tasks return to the queue. Task completion uses D1 batch transactions for the task update and result-message insert.

## Security invariants to preserve
1. Every agent has a unique random bearer token; only its SHA-256 hash is stored.
2. Owner bootstrap secret is at least 32 characters and compared without early-exit byte comparison.
3. Each agent may write only under agents/{agent-id}/...
4. main, master, protected governance/security/CI/deployment files, and secrets remain unwritable by agents.
5. Repository access is restricted to the configured GITHUB_REPOSITORY.
6. GitHub App configuration is optional as a complete set; partial configuration fails closed; repository operations return github_app_not_configured until configured.
7. No merge endpoint, main-branch write, or production deploy endpoint is introduced.
8. Audit events are appended; no API may update/delete them.
9. Secrets are environment bindings only and never committed.

## Compatibility and rollout
The first migration stage is a side-by-side runtime in this branch. It must not delete the Node Gateway or the Railway staging declaration. Existing PRs #22 and #23 remain open and unmerged. Once the Worker tests pass, open a draft PR targeting the existing foundation branch or a suitable migration integration branch; do not merge it automatically.
Before cutover, test modern MCP discovery/calls and legacy stateless initialization, REST auth, bootstrap, token rotation/revocation, task concurrent claims, lease expiry/retry, message idempotency/ack, GitHub App fail-closed behavior, repository allowlisting and branch/path protections.

## Current status
- Source inspected: PR #22 foundation and PR #23 bootstrap hardening.
- Migration branch created from PR #23 head.
- No Cloudflare account was inspected or connected.
- No Cloudflare resources were created and no deployment was run.
- Worker implementation and automated validation remain in progress.