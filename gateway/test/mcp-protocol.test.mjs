import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { createMcpFastifyApp } from "@modelcontextprotocol/fastify";
import { toNodeHandler } from "@modelcontextprotocol/node";
import { createMcpHandler, McpServer } from "@modelcontextprotocol/server";
import { Client, StreamableHTTPClientTransport } from "@modelcontextprotocol/client";
import * as z from "zod/v4";

let app;
let endpoint;

before(async () => {
  app = createMcpFastifyApp({ host: "127.0.0.1" });
  const handler = createMcpHandler(() => {
    const server = new McpServer({ name: "yara-protocol-test", version: "0.1.0" });
    server.registerTool(
      "echo",
      {
        description: "Return the supplied text.",
        annotations: { readOnlyHint: true },
        inputSchema: z.object({ text: z.string() })
      },
      async ({ text }) => ({ content: [{ type: "text", text }] })
    );
    return server;
  }, { legacy: "stateless", responseMode: "auto" });
  const nodeHandler = toNodeHandler(handler);
  app.all("/mcp", async (request, reply) => {
    reply.hijack();
    await nodeHandler(request.raw, reply.raw, request.body);
  });
  await app.listen({ host: "127.0.0.1", port: 0 });
  const address = app.server.address();
  if (!address || typeof address === "string") throw new Error("Test server did not bind to a TCP port");
  endpoint = new URL("http://127.0.0.1:" + address.port + "/mcp");
});

after(async () => {
  await app?.close();
});

async function verifyEra(expectedEra, options) {
  const client = new Client({ name: "yara-protocol-test-client", version: "0.1.0" }, options);
  try {
    await client.connect(new StreamableHTTPClientTransport(endpoint));
    assert.equal(client.getProtocolEra(), expectedEra);
    const listed = await client.listTools();
    assert.ok(listed.tools.some((tool) => tool.name === "echo"));
    const result = await client.callTool({ name: "echo", arguments: { text: expectedEra } });
    assert.equal(result.isError, undefined);
    assert.equal(result.content?.[0]?.text, expectedEra);
  } finally {
    await client.close().catch(() => {});
  }
}

test("MCP 2026-07-28 modern discovery and tool calls work", async () => {
  await verifyEra("modern", { versionNegotiation: { mode: "auto" } });
});

test("MCP 2025 legacy initialize fallback and tool calls work", async () => {
  await verifyEra("legacy");
});
