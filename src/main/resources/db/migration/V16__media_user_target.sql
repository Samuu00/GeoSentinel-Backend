-- Foto profilo: un media allegato all'utente (entity_id = id dell'utente).
ALTER TABLE media_files DROP CONSTRAINT chk_media_files_entity_type;
ALTER TABLE media_files
    ADD CONSTRAINT chk_media_files_entity_type CHECK (entity_type IN ('TRAIL', 'HAZARD_REPORT', 'REFUGE', 'USER'));