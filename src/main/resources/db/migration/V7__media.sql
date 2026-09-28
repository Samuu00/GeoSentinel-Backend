-- Il bucket è un dettaglio dell'adapter MinIO (MinioProperties): non si salva sulla riga
CREATE TABLE media_files (
    id                uuid PRIMARY KEY,
    owner_id          uuid         NOT NULL,
    -- generata dal server: yyyy/MM/uuid.ext
    object_key        varchar(64)  NOT NULL,
    original_filename varchar(255) NOT NULL,
    content_type      varchar(50)  NOT NULL,
    -- dichiarata finché PENDING_UPLOAD, poi quella reale
    size_bytes        bigint       NOT NULL,
    status            varchar(20)  NOT NULL,
    -- polimorfico: niente FK, l'esistenza del target la verifica MediaService
    entity_type       varchar(20),
    entity_id         uuid,
    checksum          varchar(64),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    updated_by        uuid,
    version           bigint       NOT NULL DEFAULT 0,

    CONSTRAINT fk_media_files_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT uk_media_files_object_key UNIQUE (object_key),
    CONSTRAINT chk_media_files_status CHECK (status IN ('PENDING_UPLOAD', 'READY', 'QUARANTINED')),
    CONSTRAINT chk_media_files_entity_type CHECK (entity_type IN ('TRAIL', 'HAZARD_REPORT')),
    CONSTRAINT chk_media_files_target CHECK ((entity_type IS NULL) = (entity_id IS NULL)),
    CONSTRAINT chk_media_files_size CHECK (size_bytes > 0)
);

-- Quota (sumQuotaBytes) e /media/my; copre anche la FK owner_id
CREATE INDEX idx_media_files_owner_status_created ON media_files (owner_id, status, created_at);
-- /media/attached
CREATE INDEX idx_media_files_entity ON media_files (entity_type, entity_id) WHERE status = 'READY';
-- MediaCleanupJob
CREATE INDEX idx_media_files_pending_created ON media_files (created_at) WHERE status = 'PENDING_UPLOAD';
