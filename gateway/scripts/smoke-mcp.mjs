import { Client, StreamableHTTPClientTransport } from "@modelcontextprotocol/client";

const endpoint = process.env.MCP_URL;
const token = process.env.YARA_AGENT_TOKEN;
if (!endpoint || !token) throw new Error("MCP_URL and YARA_AGENT_TOKEN are required");

const url = new URL(endpoint);
if (url.protocol !== "https:") throw new Error("MCP_URL must use HTTPS");

const requiredTools = [
  "yara_identity",
  "yara_read_file",
  "yara_create_branch",
  "yara_write_file",
  "yara_create_draft_pr"
];

function createClient(name, versionNegotiation) {
  const options = versionNegotiation ? { versionNegotiation } : {};
  const client = new Client({ name, version: "0.1.0" }, options);
  const transport = new StreamableHTTPClientTransport(url, {
    authProvider: { token: async () => token },
    reconnectionOptions: { maxRetries: 3 }
  });
  return { client, transport };
}

async function verifyClient(client, expectedEra) {
  const { tools } = await client.listTools();
  const names = new Set(tools.map((tool) => tool.name));
  for (const name of requiredTools) {
    if (!names.has(name)) throw new Error("Missing MCP tool: " + name);
  }
  const identity = await client.callTool({ name: "yara_identity", arguments: {} });
  if (identity.isError) throw new Error("Authenticated identity tool returned an error");
  const text = identity.content?.find((part) => part.type === "text")?.text;
  if (!text) throw new Error("Identity tool returned no text content");
  const parsed = JSON.parse(text);
  if (!parsed.id || parsed.status !== "active") throw new Error("Invalid agent identity response");
  if (client.getProtocolEra() !== expectedEra) {
    throw new Error("Expected " + expectedEra + " MCP era, got " + client.getProtocolEra());
  }
  return parsed.id;
}

let modern;
let legacy;
try {
  modern = createClient("yara-modern-smoke", { mode: "auto" });
  await modern.client.connect(modern.transport);
  const modernAgent = await verifyClient(modern.client, "modern");

  legacy = createClient("yara-legacy-smoke");
  await legacy.client.connect(legacy.transport);
  const legacyAgent = await verifyClient(legacy.client, "legacy");

  if (modernAgent !== legacyAgent) throw new Error("MCP protocol eras resolved different agent identities");

  const unauthenticated = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream" },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 99,
      method: "initialize",
      params: { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "unauthenticated-smoke", version: "0.1.0" } }
    })
  });
  if (unauthenticated.status !== 401) {
    throw new Error("Expected unauthenticated request to return 401; received " + unauthenticated.status);
  }

  console.log("YARA MCP smoke test passed: modern 2026-07-28, legacy 2025, tool discovery, identity and 401 denial.");
} finally {
  await modern?.client.close().catch(() => {});
  await legacy?.client.close().catch(() => {});
}
