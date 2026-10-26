ALTER TABLE documents ADD COLUMN content_hash VARCHAR(64);
ALTER TABLE documents ADD COLUMN origin_type VARCHAR(30) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE documents ADD COLUMN origin_id VARCHAR(100);
ALTER TABLE documents ADD COLUMN chunk_size INT NOT NULL DEFAULT 700;
ALTER TABLE documents ADD COLUMN chunk_overlap INT NOT NULL DEFAULT 100;
CREATE UNIQUE INDEX idx_documents_content_hash ON documents(user_id,content_hash);
