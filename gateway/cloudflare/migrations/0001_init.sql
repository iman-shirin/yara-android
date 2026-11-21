PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS yara_agents (
  id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  revoked_at TEXT
);

CREATE TABLE IF NOT EXISTS yara_tasks (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  created_by TEXT NOT NULL REFERENCES yara_agents(id),
  assigned_to TEXT REFERENCES yara_agents(id),
  status TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','running','completed','failed','cancelled')),
  attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  max_attempts INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
  lease_until TEXT,
  result TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  completed_at TEXT
);
CREATE INDEX IF NOT EXISTS yara_tasks_queue_idx ON yara_tasks(status, created_at) WHERE status = 'queued';
CREATE INDEX IF NOT EXISTS yara_tasks_assignee_idx ON yara_tasks(assigned_to, status, updated_at DESC);

CREATE TABLE IF NOT EXISTS yara_messages (
  id TEXT PRIMARY KEY,
  task_id TEXT REFERENCES yara_tasks(id) ON DELETE CASCADE,
  sender_id TEXT NOT NULL REFERENCES yara_agents(id),
  recipient_id TEXT REFERENCES yara_agents(id),
  kind TEXT NOT NULL CHECK (kind IN ('message','task_update','result','request')),
  body TEXT NOT NULL,
  idempotency_key TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  read_at TEXT,
  UNIQUE(sender_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS yara_messages_inbox_idx ON yara_messages(recipient_id, created_at) WHERE read_at IS NULL;
CREATE INDEX IF NOT EXISTS yara_messages_task_idx ON yara_messages(task_id, created_at);

CREATE TABLE IF NOT EXISTS yara_audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  occurred_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  agent_id TEXT,
  action TEXT NOT NULL,
  resource TEXT NOT NULL,
  outcome TEXT NOT NULL,
  request_id TEXT,
  details TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS yara_audit_agent_time_idx ON yara_audit(agent_id, occurred_at DESC);
CREATE TRIGGER IF NOT EXISTS yara_audit_no_update BEFORE UPDATE ON yara_audit BEGIN SELECT RAISE(ABORT, 'yara_audit is append-only'); END;
CREATE TRIGGER IF NOT EXISTS yara_audit_no_delete BEFORE DELETE ON yara_audit BEGIN SELECT RAISE(ABORT, 'yara_audit is append-only'); END;