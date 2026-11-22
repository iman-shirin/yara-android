CREATE TABLE IF NOT EXISTS yara_message_receipts (
  message_id TEXT NOT NULL REFERENCES yara_messages(id) ON DELETE CASCADE,
  agent_id TEXT NOT NULL REFERENCES yara_agents(id) ON DELETE CASCADE,
  read_at TEXT,
  PRIMARY KEY (message_id, agent_id)
);
CREATE INDEX IF NOT EXISTS yara_message_receipts_agent_unread_idx
  ON yara_message_receipts(agent_id, read_at, message_id);
