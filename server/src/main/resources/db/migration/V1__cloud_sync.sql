CREATE TABLE cloud_accounts (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    change_seq BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE cloud_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    expires_at BIGINT NOT NULL,
    FOREIGN KEY (owner_id) REFERENCES cloud_accounts(id)
);
CREATE INDEX cloud_sessions_expiry ON cloud_sessions(expires_at);
CREATE TABLE cloud_records (
    owner_id VARCHAR(36) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    record_id VARCHAR(36) NOT NULL,
    server_version BIGINT NOT NULL,
    payload LONGTEXT NOT NULL,
    PRIMARY KEY (owner_id, kind, record_id),
    FOREIGN KEY (owner_id) REFERENCES cloud_accounts(id)
);
CREATE TABLE cloud_changes (
    owner_id VARCHAR(36) NOT NULL,
    seq BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    record_id VARCHAR(36) NOT NULL,
    server_version BIGINT NOT NULL,
    payload LONGTEXT NOT NULL,
    PRIMARY KEY (owner_id, seq),
    FOREIGN KEY (owner_id) REFERENCES cloud_accounts(id)
);
CREATE TABLE cloud_operations (
    owner_id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    request_json LONGTEXT NOT NULL,
    response_json LONGTEXT NOT NULL,
    PRIMARY KEY (owner_id, operation_id),
    FOREIGN KEY (owner_id) REFERENCES cloud_accounts(id)
);
