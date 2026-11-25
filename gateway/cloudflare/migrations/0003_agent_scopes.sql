ALTER TABLE yara_agents ADD COLUMN scopes TEXT NOT NULL DEFAULT '["project:read"]';
