ALTER TABLE users ADD COLUMN email VARCHAR(254) NULL;
CREATE UNIQUE INDEX uk_users_email ON users(email);
