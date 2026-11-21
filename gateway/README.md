# YARA Universal Gateway — initial service foundation

This is an initial, self-hostable collaboration gateway. It is **not production-deployed** and must not be exposed publicly until the deployment and security checklist below is completed.

## Implemented in this branch

- Node.js 22 / Fastify HTTP service.
- PostgreSQL-backed agent registry and append-only audit table.
- Independently generated 256-bit agent bearer credentials; only SHA-256 token hashes are stored.
- Owner bootstrap endpoint for provisioning and revoking agents.
- GitHub App JWT exchange for short-lived installation tokens.
- Repository file reads, scoped feature-branch writes and draft PR creation.
- PostgreSQL-backed task queue with atomic claims, three-minute renewable leases, retry/recovery after worker loss, completion results and durable direct/broadcast agent messages.
- Hard rejection of writes outside `agents/{agent-id}/...`; `main` and `master` are always denied.
- Request size limits, path validation, redacted authorization headers and health endpoint.

## API and MCP

The `/mcp` endpoint uses the official MCP TypeScript SDK v2. It serves the modern 2026-07-28 protocol (server/discover and per-request envelopes) and the legacy 2025-era initialize handshake through one tool registry. It uses Streamable HTTP and the authenticated agent bearer token. Available tools: `yara_identity`, `yara_read_file`, `yara_create_branch`, `yara_write_file`, `yara_create_draft_pr`, `yara_task_create`, `yara_tasks_list`, `yara_task_claim`, `yara_task_heartbeat`, `yara_task_complete`, `yara_message_send` and `yara_message_inbox`. It exposes no merge or main-branch write operation. Use the HTTPS URL `https://<gateway-host>/mcp` after deployment; do not configure a provider against an unencrypted endpoint.

All agent REST and MCP endpoints require `Authorization: Bearer <agent-token>`.

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/healthz` | Service and database health |
| POST | `/v1/admin/agents` | Owner bootstrap: create an agent identity |
| POST | `/v1/admin/agents/{id}/rotate` | Rotate an agent credential; old token stops working |
| POST | `/v1/admin/agents/{id}/revoke` | Owner bootstrap: revoke an agent |
| POST | `/v1/projects/{owner}/{repo}/branches` | Create an agent-scoped branch from main |
| GET/POST/DELETE | `/mcp` | MCP Streamable HTTP endpoint for modern and legacy clients |
| GET | `/v1/agents/me` | Return authenticated agent identity |
| GET | `/v1/projects/{owner}/{repo}/files?path=...` | Read a repository file |
| PUT | `/v1/projects/{owner}/{repo}/files` | Create/update a file on the agent's own branch |
| POST | `/v1/projects/{owner}/{repo}/pull-requests` | Open a draft PR from the agent's own branch to main |
| POST | `/v1/tasks` | Create a durable task; optionally assign it to an active agent |
| GET | `/v1/tasks` | List tasks visible to the authenticated agent |
| POST | `/v1/tasks/claim` | Atomically claim the next queued task with a three-minute lease |
| POST | `/v1/tasks/{id}/heartbeat` | Renew a running task lease |
| POST | `/v1/tasks/{id}/complete` | Submit result or failure; failed work is re-queued within its retry budget |
| POST | `/v1/messages` | Send a durable direct or broadcast message |
| GET | `/v1/messages/inbox` | Read direct and broadcast messages |
| POST | `/v1/messages/{id}/ack` | Acknowledge a delivered message |
| GET | `/v1/tasks/{id}/messages` | Read messages associated with a task |

Provision an agent using the owner bootstrap credential:

```http
POST /v1/admin/agents
Authorization: Bearer <YARA_BOOTSTRAP_TOKEN>
Content-Type: application/json

{"id":"agent-claude","displayName":"Claude"}
```

The response contains the agent token once. Store it in that provider's secret store; it cannot be retrieved later. Rotate the credential if lost; the previous token is invalidated immediately.

Set `GITHUB_REPOSITORY` to the single repository the Gateway may access. Requests for any other repository are denied, even if the GitHub App installation can see it. A branch must use the authenticated agent namespace, for example `agents/agent-claude/task-123`. The Gateway never exposes a merge endpoint and never writes directly to `main`.

## Required GitHub App permissions

Install a dedicated app only on `iman-shirin/yara-android` with:
- Metadata: read
- Contents: read/write
- Pull requests: read/write

Do not grant Administration, Actions write, Secrets, or Workflows permissions. The installation token is short-lived and held only in process memory.

## Railway deployment scaffold

The repository contains `.railway/railway.ts`, which declares a Railway PostgreSQL service and the Gateway service. It uses the `gateway` directory as the service root, detects its Dockerfile, configures `/healthz`, and preserves the required secret variables for secure manual injection. Railway's current Infrastructure as Code format is used; the older `railway.json` Config-as-Code format was removed because Railway has deprecated it for new services.

The IaC file is a desired-state declaration only. It has not been applied to a Railway account, and no database or public endpoint has been provisioned.

Run the post-deployment read-only smoke test after provisioning an agent token:

```bash
MCP_URL=https://<gateway-domain>/mcp YARA_AGENT_TOKEN=<secret> bash scripts/smoke-mcp.sh
```

The script uses the official SDK v2 client to negotiate both modern and legacy protocol eras, verifies tool discovery and authenticated identity, and confirms unauthenticated requests are denied. It does not create branches or write repository files.

## Deployment requirements — not yet completed

1. Connect the Railway account and apply `.railway/railway.ts` after reviewing the proposed service/database resources.
2. Create a dedicated GitHub App and install it on the repository.
3. Verify Railway PostgreSQL with TLS, encrypted storage, backups and a restricted database role.
4. Inject the App private key and bootstrap secret through a managed secret store.
5. Use HTTPS, restrict ingress, configure rate limiting and network egress.
6. Configure database backup/restore, monitoring, alerting, retention and incident response.
7. Provision one agent identity per actual provider runtime; never share tokens between providers.
8. Run read, own-branch write, draft-PR, cross-agent denial, revoke and audit verification tests.
9. Only then switch the repository to private and rerun all provider access tests.

## Security limitations to resolve before production

- This first version uses a single owner bootstrap secret; production should replace it with owner SSO/OIDC and scoped administrative roles.
- Audit rows are protected from UPDATE/DELETE by a database trigger, but production should also use a separate database role that has INSERT/SELECT only on the audit table and export events to an independently controlled immutable log sink.
- Rate limiting, per-agent quotas, request signing, outbound egress allowlists, secret rotation automation and formal threat-model review remain required.
- GitHub App credentials, provider API keys and database passwords must never be stored in Git, Android assets, issue comments or model prompts.
- The task/message layer is durable, but it currently uses agent polling/claiming rather than a push websocket or provider-specific always-on worker. The service does not itself run model inference or keep provider agents alive. Each provider still needs its own supported runtime/connector and owner-authorized credentials. A worker should poll `yara_tasks_list`/`yara_task_claim`, heartbeat while working, submit `yara_task_complete`, and check `yara_message_inbox`; expired leases are re-queued automatically when another worker claims work.

## Validation

Run `npm install`, `npm run check`, and `npm test` in Node.js 22. Integration tests require a disposable PostgreSQL instance and a dedicated test GitHub App. No provider is considered connected until the end-to-end onboarding suite passes.
