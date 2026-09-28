-- Colonne e lunghezze allineate a User / RefreshToken: validate controlla solo nomi e tipi, non NOT NULL né lunghezze
CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         varchar(254) NOT NULL,
    password_hash varchar(255) NOT NULL,
    display_name  varchar(100) NOT NULL,
    role          varchar(20)  NOT NULL,
    enabled       boolean      NOT NULL DEFAULT TRUE,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    updated_by    uuid,
    version       bigint       NOT NULL DEFAULT 0,

    CONSTRAINT uk_users_email UNIQUE (email),
    -- il service salva e cerca l'email già normalizzata: WHERE email = ? usa l'indice unico
    CONSTRAINT chk_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT chk_users_role CHECK (role IN ('HIKER', 'PARK_MANAGER', 'RESCUE_OPERATOR', 'ADMIN'))
);

CREATE TABLE refresh_tokens (
    id                uuid PRIMARY KEY,
    user_id           uuid        NOT NULL,
    -- varchar e non char: con validate Hibernate si aspetta varchar per un String e rifiuta bpchar
    token_hash        varchar(64) NOT NULL,
    family_id         uuid        NOT NULL,
    expires_at        timestamptz NOT NULL,
    -- scadenza assoluta della sessione, copiata a ogni rotazione
    family_expires_at timestamptz NOT NULL,
    revoked_at        timestamptz,
    created_at        timestamptz NOT NULL,

    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
-- RefreshTokenCleanupJob: DELETE ... WHERE family_expires_at < :cutoff
CREATE INDEX idx_refresh_tokens_family_expires ON refresh_tokens (family_expires_at);
