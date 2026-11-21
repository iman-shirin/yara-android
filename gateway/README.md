# YARA Universal Gateway — initial service foundation

This is an initial, self-hostable collaboration gateway. It is **not production-deployed** and must not be exposed publicly until the deployment and security checklist below is completed.

## Implemented in this branch

- Node.js 22 / Fastify HTTP service.
- PostgreSQL-backed agent registry and append-only audit table.
- Independently generated 256-bit agent bearer credentials; only SHA-256 token hashes are stored.
- Owner bootstrap endpoint for provisioning and revoking agents.
- GitHub App JWT exchange for short-lived installation tokens.
- Repository file reads, scoped feature-branch writes and draft PR creation.
- Hard rejection of writes outside `agents/{agent-id}/...`; `main` and `master` are always denied.
- Request size limits, path validation, redacted authorization headers and health endpoint.

## API and MCP

The stateless MCP endpoint is `POST /mcp`. It supports JSON-RPC initialization, tool discovery and tool calls using the authenticated agent bearer token. Available tools: `yara_identity`, `yara_read_file`, `yara_create_branch`, `yara_write_file` and `yara_create_draft_pr`. It exposes no merge or main-branch write operation. Use the HTTPS URL `https://<gateway-host>/mcp` after deployment; do not configure a provider against an unencrypted endpoint.\n\nAll agent REST and MCP endpoints require `Authorization: Bearer <agent-token>`.

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/healthz` | Service and database health |
| POST | `/v1/admin/agents` | Owner bootstrap: create an agent identity |\n| POST | `/v1/admin/agents/{id}/rotate` | Rotate an agent credential; old token stops working |
| POST | `/v1/admin/agents/{id}/revoke` | Owner bootstrap: revoke an agent |
| POST | `/v1/projects/{owner}/{repo}/branches` | Create an agent-scoped branch from main |\n| POST | `/mcp` | Stateless MCP endpoint for the tools listed above |\n| GET | `/v1/agents/me` | Return authenticated agent identity |
| GET | `/v1/projects/{owner}/{repo}/files?path=...` | Read a repository file |
| PUT | `/v1/projects/{owner}/{repo}/files` | Create/update a file on the agent's own branch |
| POST | `/v1/projects/{owner}/{repo}/pull-requests` | Open a draft PR from the agent's own branch to main |

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

## Deployment requirements — not yet completed

1. Create a dedicated GitHub App and install it on the repository.
2. Provision PostgreSQL with TLS, encrypted storage, backups and a restricted database role.
3. Inject the App private key and bootstrap secret through a managed secret store.
4. Use a TLS reverse proxy, restrict ingress, configure rate limiting and network egress.
5. Configure database backup/restore, monitoring, alerting, retention and incident response.
6. Provision one agent identity per actual provider runtime; never share tokens between providers.
7. Run read, own-branch write, draft-PR, cross-agent denial, revoke and audit verification tests.
8. Only then switch the repository to private and rerun all provider access tests.

## Security limitations to resolve before production

- This first version uses a single owner bootstrap secret; production should replace it with owner SSO/OIDC and scoped administrative roles.
- Audit rows are protected from UPDATE/DELETE by a database trigger, but production should also use a separate database role that has INSERT/SELECT only on the audit table and export events to an independently controlled immutable log sink.
- Rate limiting, per-agent quotas, request signing, outbound egress allowlists, secret rotation automation and formal threat-model review remain required.
- GitHub App credentials, provider API keys and database passwords must never be stored in Git, Android assets, issue comments or model prompts.
- The service does not itself run model inference or keep provider agents alive. Each provider still needs its own supported runtime/connector and owner-authorized credentials.

## Validation

Run `npm install`, `npm run check`, and `npm test` in Node.js 22. Integration tests require a disposable PostgreSQL instance and a dedicated test GitHub App. No provider is considered connected until the end-to-end onboarding suite passes.
