CREATE TABLE password_reset_tokens (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL REFERENCES users (id),
    code_hash       varchar(100) NOT NULL,   -- BCrypt con prefisso {bcrypt}
    expires_at      timestamptz NOT NULL,
    consumed_at     timestamptz,
    failed_attempts smallint NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    created_at      timestamptz NOT NULL
);

CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens (user_id, created_at DESC);
CREATE INDEX idx_password_reset_tokens_expires ON password_reset_tokens (expires_at);