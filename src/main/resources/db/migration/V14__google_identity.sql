ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE user_identities (
    id        uuid PRIMARY KEY,
    user_id   uuid         NOT NULL,
    provider  varchar(20)  NOT NULL,
    subject   varchar(255) NOT NULL,
    email     varchar(254) NOT NULL,
    linked_at timestamptz  NOT NULL,

    CONSTRAINT fk_user_identities_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- una sola identità per account Google: il `sub` è stabile, l'email no
    CONSTRAINT uk_user_identities_provider_subject UNIQUE (provider, subject),
    CONSTRAINT chk_user_identities_provider CHECK (provider IN ('GOOGLE'))
);

CREATE INDEX idx_user_identities_user ON user_identities (user_id);