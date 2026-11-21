#!/usr/bin/env bash
set -euo pipefail

: "${MCP_URL:?Set MCP_URL to the public HTTPS Gateway endpoint, including /mcp}"
: "${YARA_AGENT_TOKEN:?Set YARA_AGENT_TOKEN from the provider-specific secret store}"

case "$MCP_URL" in
  https://*) ;;
  *) echo "MCP_URL must use HTTPS" >&2; exit 2 ;;
esac

node "$(dirname "$0")/smoke-mcp.mjs"
