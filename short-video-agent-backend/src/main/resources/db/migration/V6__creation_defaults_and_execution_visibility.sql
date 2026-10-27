ALTER TABLE conversations ADD COLUMN executions_cleared_through BIGINT NOT NULL DEFAULT 0;
CREATE TABLE user_creation_defaults (
 user_id VARCHAR(36) PRIMARY KEY,
 brief_json LONGTEXT NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
