-- ADR-0019: periodo di grazia sul refresh token appena ruotato.
-- replaced_by_id = token emesso dalla rotazione. Distingue un token revocato da una rotazione (ha un successore)
-- da uno revocato da logout, cambio password o furto (non ce l'ha): solo il primo può avere la grazia.
ALTER TABLE refresh_tokens
    ADD COLUMN replaced_by_id uuid,
    ADD CONSTRAINT fk_refresh_tokens_replaced_by
        FOREIGN KEY (replaced_by_id) REFERENCES refresh_tokens (id) ON DELETE SET NULL;
