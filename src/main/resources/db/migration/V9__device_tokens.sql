-- Token FCM dei dispositivi (M7.4). Un token appartiene a un solo utente: lo stesso telefono con un altro account
-- lo riassegna (upsert su token). Niente ruolo qui: i destinatari per ruolo li calcola identity a ogni invio
CREATE TABLE device_tokens (
    id           uuid PRIMARY KEY,
    user_id      uuid          NOT NULL,
    -- 1024 e non 4096: un indice B-tree rifiuta righe oltre ~2700 byte (i token FCM sono ~160 caratteri)
    token        varchar(1024) NOT NULL,
    platform     varchar(10)   NOT NULL,
    last_seen_at timestamptz   NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   uuid,

    CONSTRAINT fk_device_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_device_tokens_token UNIQUE (token),
    CONSTRAINT chk_device_tokens_platform CHECK (platform IN ('ANDROID', 'IOS'))
);

-- tokensOf / deleteAllOf; copre anche la FK user_id
CREATE INDEX idx_device_tokens_user ON device_tokens (user_id);
