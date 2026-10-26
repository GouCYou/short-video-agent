ALTER TABLE agent_runs ADD COLUMN retry_message_id VARCHAR(36);
ALTER TABLE documents ADD COLUMN user_id VARCHAR(36);
ALTER TABLE documents ADD COLUMN default_key VARCHAR(60);
ALTER TABLE documents ADD CONSTRAINT fk_document_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
CREATE INDEX idx_documents_user ON documents(user_id,created_at);
CREATE UNIQUE INDEX idx_documents_default ON documents(user_id,default_key);
CREATE TABLE user_knowledge_settings (
 user_id VARCHAR(36) PRIMARY KEY,
 initialized BOOLEAN NOT NULL DEFAULT TRUE,
 FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
