CREATE TABLE user_mfa (
    user_id        uuid PRIMARY KEY REFERENCES users(id),
    secret_enc     bytea        NOT NULL,
    enabled_at     timestamptz,
    last_used_step bigint,
    created_at     timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE mfa_recovery_codes (
    id         uuid PRIMARY KEY,
    user_id    uuid         NOT NULL REFERENCES users(id),
    code_hash  varchar(64)  NOT NULL,
    used_at    timestamptz,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_mfa_recovery_user_hash UNIQUE (user_id, code_hash)
);