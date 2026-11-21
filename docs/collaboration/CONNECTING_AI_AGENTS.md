# Connecting AI agents to the private YARA project

**Owner:** Iman  
**Repository:** `iman-shirin/yara-android`  
**Target access model:** Private GitHub repository + YARA Universal Gateway

## The key principle

All AI assistants work on one canonical YARA project, but they do not share the owner's GitHub password or personal access token. The YARA Gateway is the controlled access point.

A private repository does not prevent multi-provider collaboration. It prevents anonymous/public access. Authorized agents reach repository content through a dedicated GitHub App installation, or through provider-specific OAuth/app integrations explicitly granted access.

## Connection topology

```text
ChatGPT ─┐
Claude ──┤
Gemini ──┤      Provider-specific authentication
Grok ────┼────> YARA Gateway ──> YARA GitHub App ──> Private GitHub repository
Perplexity┤          │
Copilot ──┘          ├── Shared project knowledge
                     ├── Mission / task / message store
                     ├── Policy and approval service
                     └── Append-only audit log
```

## GitHub App permissions

Create a dedicated GitHub App owned by the project owner and install it only on `iman-shirin/yara-android`.

Start with minimum permissions:
- Repository metadata: read.
- Contents: read/write (feature branches only, enforced by Gateway policy).
- Pull requests: read/write.
- Issues: read/write only if task and review coordination requires it.
- Checks and Actions: read-only for CI status and artifacts where needed.
- Administration: none.
- Secrets: none.
- Workflows: no write permission unless a separately reviewed requirement proves it necessary.

The Gateway exchanges its app identity for short-lived installation tokens. Never place a GitHub App private key or installation token in a model prompt, mobile app, repository file, issue, or shared memory.

## Provider connection methods

The initial Gateway code now includes a stateless JSON-RPC MCP endpoint at `POST /mcp` with authenticated tools for identity, repository reads, agent-scoped branch creation, file writes and draft PR creation. It is not deployed yet. The Gateway exposes a stable, authenticated tool surface. Prefer remote MCP for clients that support it. Use a vendor-specific adapter/API runtime for clients that do not support the required remote protocol. Do not claim an integration is live until its end-to-end tests pass.

| Assistant | Verified connection path supported by current client/product docs | Required owner action |
|---|---|---|
| ChatGPT | Existing GitHub integration is connected and has repository read/write. Custom remote MCP is not available on the current Free plan; a separate OpenAI API runtime is possible through the Responses API with separate API billing. | Keep the GitHub connection authorized; API runtime requires a separate API project/key if chosen. |
| Claude | Remote MCP custom connector is supported on Free/Pro/Max/Ultra/Team/Enterprise; Free is limited to one custom connector. | Add the deployed HTTPS MCP endpoint and the Claude-specific bearer token. |
| Gemini | Gemini CLI and Gemini API managed-agent runtimes support remote MCP; the consumer Gemini chat surface is not assumed to support custom MCP. | Configure the selected CLI/API runtime with its own token. |
| Grok | xAI Grok CLI supports remote HTTP MCP and static authorization headers; consumer Grok surfaces are not assumed to support custom MCP. | Configure the selected CLI/runtime with its own token. |
| Perplexity | Custom remote MCP connectors are supported on Pro, Max and Enterprise. | Add the deployed HTTPS endpoint and the Perplexity-specific token. |
| GitHub Copilot | Remote MCP is supported in Copilot CLI, Copilot app, IDEs and cloud agent. Repository-level cloud-agent configuration requires repository administrator setup. | Configure the selected Copilot surface and store its dedicated token in the supported secret store. |

These are integration targets, not claims that all six are currently connected. Product capabilities vary by plan, client and release; verify the exact client before enabling each adapter.

## Agent identity and permission policy

Each provider/runtime receives a distinct YARA agent identity, for example:
- `agent-chatgpt`
- `agent-claude`
- `agent-gemini`
- `agent-grok`
- `agent-perplexity`
- `agent-copilot`

Each identity has:
- its own authentication method and revocation handle;
- an explicit capability list;
- read scope for approved project files and knowledge;
- write scope for assigned feature branches and task artifacts;
- no permission to write, merge, force-push or delete on `main`;
- a per-agent audit trail and rate/cost limits.

A task may grant temporary, narrower capabilities. Delegation cannot expand the delegator's rights. The Gateway rechecks authorization at execution time and fails closed when approval is missing.

## Required end-to-end onboarding tests

For each assistant, independently verify:
1. Authenticate to the Gateway and identify the agent ID.
2. Read a harmless project status resource.
3. Read an approved source/document file.
4. Create or update a test artifact on that agent's feature branch.
5. Open a draft PR or task report.
6. Verify the commit, author identity and audit receipt.
7. Revoke the agent and confirm further reads/writes are denied.

An assistant is marked **Connected (Read/Write)** only after all applicable tests pass. Merely adding a provider name to a configuration file is not a connection.

## Safe transition to private visibility

The repository is currently public. Do not flip it to private until:
1. the dedicated GitHub App is installed and verified against the repository;
2. the Gateway has passed GitHub App read/write branch tests;
3. the current ChatGPT integration path is confirmed to retain authorized access or is replaced by the Gateway connection;
4. the owner has authorized each provider's OAuth/API credentials.

Then the owner changes repository visibility to private in GitHub settings. Immediately rerun all provider read/write tests. Keep the owner as the only identity able to approve merges to `main`.

## Current verified state (2026-11-21)

- GitHub repository: `iman-shirin/yara-android`.
- Visibility: public.
- Connected ChatGPT GitHub integration can read and write this repository.
- An isolated collaboration foundation branch and draft PR #22 exist.
- No dedicated YARA GitHub App or YARA Gateway has been deployed.
- No Claude, Gemini, Grok, Perplexity or Copilot runtime has been verified through the Gateway.
- No repository visibility change has been made.


For provider-specific setup commands, plan limitations and per-client authentication notes, see [PROVIDER_CONNECTIONS.md](./PROVIDER_CONNECTIONS.md).
