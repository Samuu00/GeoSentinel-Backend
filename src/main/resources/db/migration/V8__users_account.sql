-- Supporto per cancellazione/anonimizzazione account
ALTER TABLE users
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ADD COLUMN age_confirmed_at TIMESTAMPTZ NULL;

CREATE INDEX idx_users_deleted_at ON users (deleted_at) WHERE deleted_at IS NOT NULL;