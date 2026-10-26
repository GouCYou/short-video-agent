CREATE TABLE users (
 id VARCHAR(36) PRIMARY KEY,
 username VARCHAR(40) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 nickname VARCHAR(80) NOT NULL,
 role VARCHAR(10) NOT NULL DEFAULT 'USER',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 session_version INT NOT NULL DEFAULT 1,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE tasks (
 id VARCHAR(36) PRIMARY KEY,
 user_id VARCHAR(36) NOT NULL,
 title VARCHAR(200) NOT NULL,
 payload LONGTEXT NOT NULL,
 status VARCHAR(30) NOT NULL,
 stage VARCHAR(60) NOT NULL,
 error TEXT,
 topics LONGTEXT NOT NULL,
 selected_topic LONGTEXT,
 result LONGTEXT,
 sources LONGTEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (user_id) REFERENCES users(id),
 INDEX idx_tasks_owner_time (user_id, created_at),
 INDEX idx_tasks_status (status)
);
CREATE TABLE versions (
 id VARCHAR(36) PRIMARY KEY,
 task_id VARCHAR(36) NOT NULL,
 version_number INT NOT NULL,
 kind VARCHAR(30) NOT NULL,
 instruction TEXT,
 content LONGTEXT NOT NULL,
 sources LONGTEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
 UNIQUE (task_id, version_number)
);
CREATE TABLE task_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 task_id VARCHAR(36) NOT NULL,
 stage VARCHAR(60) NOT NULL,
 message TEXT NOT NULL,
 duration_ms BIGINT,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
 INDEX idx_events_task (task_id,id)
);
CREATE TABLE documents (
 id VARCHAR(36) PRIMARY KEY,
 name VARCHAR(200) NOT NULL,
 category VARCHAR(100) NOT NULL,
 source_url VARCHAR(2000) NOT NULL DEFAULT '',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 status VARCHAR(30) NOT NULL,
 chunk_count INT NOT NULL DEFAULT 0,
 error TEXT,
 storage_path VARCHAR(1000),
 raw_text LONGTEXT,
 chunks LONGTEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE jobs (
 id VARCHAR(36) PRIMARY KEY,
 target_type VARCHAR(20) NOT NULL,
 target_id VARCHAR(36) NOT NULL,
 phase VARCHAR(20) NOT NULL,
 payload LONGTEXT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
 worker_id VARCHAR(36),
 attempts INT NOT NULL DEFAULT 0,
 error TEXT,
 duration_ms BIGINT,
 usage_json LONGTEXT,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_jobs_pending (status,created_at),
 INDEX idx_jobs_target (target_type,target_id)
);
CREATE TABLE task_requests (
 user_id VARCHAR(36) NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 task_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(user_id,request_key),
 FOREIGN KEY (user_id) REFERENCES users(id),
 FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
);
