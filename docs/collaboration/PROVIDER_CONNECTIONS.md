# YARA provider connection runbook

This guide records which client surfaces can connect to a remote YARA MCP endpoint. A provider name is not a connected agent: mark it connected only after the identity, read, own-branch write, draft-PR and revocation checks pass.

## Current connection state (2026-11-21)

| Provider | Current state | Evidence / next step |
|---|---|---|
| ChatGPT | Existing GitHub connection is authorized with repository read/write access. | Re-test after the repository is made private; custom MCP is not available on the current Free plan. |
| Claude | Not connected to YARA Gateway. | Waiting for Gateway deployment and a Claude-specific agent token. |
| Gemini | Not connected to YARA Gateway. | Waiting for a selected Gemini CLI/API runtime and its agent token. |
| Grok | Not connected to YARA Gateway. | Waiting for Grok CLI/runtime configuration and its agent token. |
| Perplexity | Not connected to YARA Gateway. | Waiting for an eligible Perplexity plan, deployed endpoint and agent token. |
| GitHub Copilot | Not connected to YARA Gateway. | Waiting for repository/IDE MCP configuration and a Copilot-specific token. |

The Gateway is currently code-tested but not deployed. The repository is still public. Do not mark any provider as connected until the post-deployment smoke test and provider-specific read/write/revoke acceptance tests pass.

## Common prerequisites

- Deploy the Gateway and expose only its HTTPS `/mcp` endpoint publicly. Keep PostgreSQL on the host's private network.
- Provision one YARA agent identity per provider runtime using the owner bootstrap endpoint.
- Store each bearer token in that provider's secret/header store. Never paste it into a prompt, source file, issue, PR or shared chat.
- Use a different token for every provider and revoke it independently.
- Enable only the tools required by the client. Keep branch and protected-path restrictions active.

The Gateway uses the official MCP TypeScript SDK v2 and negotiates the modern 2026-07-28 protocol or falls back to the legacy 2025-era handshake. It uses Streamable HTTP and a fixed `Authorization: Bearer ...` request header. It does not implement OAuth authorization-code flows. See the [official protocol-era guide](https://ts.sdk.modelcontextprotocol.io/v2/protocol-versions.html).

## Claude (claude.ai, Claude Desktop, Cowork, Claude Code)

Custom remote MCP connectors are supported on Free, Pro, Max, Ultra, Team and Enterprise plans. Free accounts are limited to one custom connector.

For claude.ai / Claude Desktop:
1. Open **Customize → Connectors**.
2. Choose **Add custom connector** and enter the public HTTPS endpoint, including `/mcp`.
3. Select fixed request-header authentication if prompted.
4. Add `Authorization: Bearer <the Claude agent token>` as a request header.
5. Connect the custom connector and enable it in the conversation.

For Claude Code, configure a remote HTTP MCP server using its supported MCP command/configuration. Use the same individual Claude agent token.

## Gemini CLI / Gemini API agent runtime

Gemini CLI supports remote Streamable HTTP MCP servers and custom headers. Example:

```bash
gemini mcp add --transport http yara https://<gateway-domain>/mcp \
  --header 'Authorization: Bearer $YARA_AGENT_TOKEN'
```

For a managed Gemini API agent, register the remote MCP server and provide the Authorization header through the runtime's credential mechanism. This is an API/runtime integration, not a claim that the consumer Gemini chat UI has a custom MCP connector.

## Grok CLI

The xAI Grok CLI supports remote HTTP MCP servers and static authorization headers. Example:

```bash
grok mcp add --transport http yara https://<gateway-domain>/mcp \
  --header 'Authorization: Bearer $YARA_AGENT_TOKEN'
```

This verifies the Grok CLI path. Do not infer that every Grok consumer surface supports custom MCP connectors.

## Perplexity

Perplexity custom remote MCP connectors are available on Pro, Max and Enterprise plans. Add a custom connector using the public HTTPS Gateway endpoint and configure its bearer-token/API-key header using the individual Perplexity agent token.

The Perplexity API Platform's own MCP server is a separate service and is not the YARA Gateway.

## GitHub Copilot

Supported surfaces include Copilot CLI, the GitHub Copilot app, IDE integrations and Copilot cloud agent. For a repository-wide cloud-agent configuration, a repository administrator can use **Repository Settings → Copilot → MCP servers**.

Example configuration (store the token in the supported Copilot secret store; do not place a real token in this JSON):

```json
{
  "mcpServers": {
    "yara": {
      "type": "http",
      "url": "https://<gateway-domain>/mcp",
      "headers": {
        "Authorization": "Bearer ${COPILOT_MCP_YARA_TOKEN}"
      },
      "tools": [
        "yara_identity",
        "yara_read_file",
        "yara_create_branch",
        "yara_write_file",
        "yara_create_draft_pr"
      ]
    }
  }
}
```

For Copilot code review, only tools annotated with `readOnlyHint: true` are eligible. YARA marks identity and file-read tools read-only; branch creation, file writes and PR creation are marked as write actions. Copilot cloud-agent calls can run autonomously without per-call approval, so use the narrowest tool allowlist and a dedicated Copilot token.

## ChatGPT

The existing GitHub connection is the supported path for this ChatGPT account and is already authorized for the YARA repository. Keep it active during the private-repository transition and re-test it immediately after visibility changes.

Custom remote MCP connections in the ChatGPT app are not available on the current Free plan. Full custom MCP write support is available for Business and Enterprise/Edu workspaces; Pro currently has read/fetch-only custom MCP access. Custom MCP setup is web-only, not in the mobile app.

A separate OpenAI API runtime can call remote MCP through the Responses API, but that requires a separately configured OpenAI API project/key and API billing. It is not the same as connecting this ChatGPT Free account to the Gateway.

## Current implementation limits

- This Gateway provides authenticated repository identity/read, agent-scoped branch creation, protected-path-aware file writes and draft PR creation.
- It has no merge endpoint and rejects writes to `main`, another agent's branch and protected configuration/secrets paths.
- Durable mission/task/message storage and A2A agent-to-agent task exchange are not implemented yet. MCP tool access alone does not create an autonomous shared task bus.
- No provider is considered connected until its individual end-to-end tests and token-revocation test pass.
