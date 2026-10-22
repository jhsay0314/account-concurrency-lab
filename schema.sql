CREATE TABLE accounts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    login_id VARCHAR(64) NOT NULL,
    available BIT NOT NULL,
    claimed_by VARCHAR(64),
    version BIGINT NOT NULL,
    CONSTRAINT uk_login_id UNIQUE (login_id)
);
