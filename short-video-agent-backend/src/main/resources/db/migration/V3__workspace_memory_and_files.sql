ALTER TABLE conversations ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE conversations ADD COLUMN title_manual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE conversations ADD COLUMN summary_text TEXT;
ALTER TABLE conversations ADD COLUMN memory_version INT NOT NULL DEFAULT 0;
ALTER TABLE conversation_messages ADD COLUMN attachments_json LONGTEXT;
ALTER TABLE conversation_messages ADD COLUMN metadata_json LONGTEXT;
CREATE TABLE conversation_attachments (
 id VARCHAR(36) PRIMARY KEY,
 conversation_id VARCHAR(36) NOT NULL,
 name VARCHAR(200) NOT NULL,
 size_bytes BIGINT NOT NULL,
 extracted_text LONGTEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);
CREATE TABLE user_memories (
 user_id VARCHAR(36) PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 profile_json LONGTEXT NOT NULL,
 revision INT NOT NULL DEFAULT 0,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
