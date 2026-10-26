CREATE TABLE conversations (
 id VARCHAR(36) PRIMARY KEY,
 user_id VARCHAR(36) NOT NULL,
 title VARCHAR(200) NOT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'IDLE',
 brief_json LONGTEXT NOT NULL,
 active_run_id VARCHAR(36),
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (user_id) REFERENCES users(id),
 INDEX idx_conversation_owner (user_id,updated_at)
);
CREATE TABLE agent_runs (
 id VARCHAR(36) PRIMARY KEY,
 conversation_id VARCHAR(36) NOT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
 request_json LONGTEXT NOT NULL,
 worker_id VARCHAR(36),
 error TEXT,
 usage_json LONGTEXT,
 attempts INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
 INDEX idx_agent_runs_pending (status,created_at)
);
CREATE TABLE conversation_messages (
 id VARCHAR(36) PRIMARY KEY,
 conversation_id VARCHAR(36) NOT NULL,
 role VARCHAR(20) NOT NULL,
 content LONGTEXT NOT NULL,
 choices_json LONGTEXT NOT NULL,
 run_id VARCHAR(36),
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
 INDEX idx_conversation_messages (conversation_id,created_at)
);
CREATE TABLE run_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 conversation_id VARCHAR(36) NOT NULL,
 run_id VARCHAR(36) NOT NULL,
 type VARCHAR(40) NOT NULL,
 data_json LONGTEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
 FOREIGN KEY (run_id) REFERENCES agent_runs(id) ON DELETE CASCADE,
 INDEX idx_run_event_replay (conversation_id,id)
);
CREATE TABLE artifacts (
 id VARCHAR(36) PRIMARY KEY,
 conversation_id VARCHAR(36) NOT NULL UNIQUE,
 title VARCHAR(300) NOT NULL,
 content_json LONGTEXT NOT NULL,
 sources_json LONGTEXT NOT NULL,
 version_number INT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);
CREATE TABLE artifact_versions (
 id VARCHAR(36) PRIMARY KEY,
 artifact_id VARCHAR(36) NOT NULL,
 version_number INT NOT NULL,
 kind VARCHAR(20) NOT NULL,
 instruction TEXT NOT NULL,
 content_json LONGTEXT NOT NULL,
 sources_json LONGTEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (artifact_id) REFERENCES artifacts(id) ON DELETE CASCADE,
 UNIQUE (artifact_id,version_number)
);
CREATE TABLE conversation_requests (
 conversation_id VARCHAR(36) NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 run_id VARCHAR(36) NOT NULL,
 PRIMARY KEY (conversation_id,request_key),
 FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
 FOREIGN KEY (run_id) REFERENCES agent_runs(id) ON DELETE CASCADE
);
