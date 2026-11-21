#!/usr/bin/env bash
set -euo pipefail

: "${MCP_URL:?Set MCP_URL to the public HTTPS Gateway endpoint, including /mcp}"
: "${YARA_AGENT_TOKEN:?Set YARA_AGENT_TOKEN from the provider-specific secret store}"

case "$MCP_URL" in
  https://*) ;;
  *) echo "MCP_URL must use HTTPS" >&2; exit 2 ;;
esac

headers=(
  -H "Authorization: Bearer $YARA_AGENT_TOKEN"
  -H "Content-Type: application/json"
  -H "Accept: application/json, text/event-stream"
  -H "MCP-Protocol-Version: 2025-03-26"
)

initialize=$(curl -fsS -X POST "$MCP_URL" "${headers[@]}" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"yara-mcp-smoke","version":"0.1.0"}}}')

echo "$initialize" | jq -e '.result.serverInfo.name == "yara-universal-gateway"' >/dev/null

tools=$(curl -fsS -X POST "$MCP_URL" "${headers[@]}" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}')

for tool in yara_identity yara_read_file yara_create_branch yara_write_file yara_create_draft_pr; do
  echo "$tools" | jq -e --arg tool "$tool" '.result.tools | any(.name == $tool)' >/dev/null
done

identity=$(curl -fsS -X POST "$MCP_URL" "${headers[@]}" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"yara_identity","arguments":{}}}')

echo "$identity" | jq -e '.result.isError != true and (.result.content | length > 0)' >/dev/null

unauthorized_status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/list","params":{}}')

if [[ "$unauthorized_status" != "401" ]]; then
  echo "Expected unauthenticated request to return 401; received $unauthorized_status" >&2
  exit 1
fi

echo "YARA MCP smoke test passed: initialize, tools/list, identity and unauthorized denial."
