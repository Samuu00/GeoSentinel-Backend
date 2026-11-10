-- ADR-0026: lo setup della 2FA si conferma anche con un codice inviato all'email dell'account.
-- Chi ha registrato l'email di un altro (la registrazione non la verifica) non può attivare la 2FA e chiudere fuori
-- il titolare. Solo l'hash SHA-256 del codice; le colonne si svuotano all'attivazione
ALTER TABLE user_mfa
    ADD COLUMN email_code_hash       varchar(64),
    ADD COLUMN email_code_expires_at timestamptz;
