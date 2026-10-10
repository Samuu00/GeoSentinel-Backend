-- Rifugi descritti (app M8.1 bis): descrizione, quota, stagione, telefono e servizi; foto allegabili ai rifugi.
-- Tutti facoltativi: i rifugi già registrati restano validi.
ALTER TABLE refuges
    ADD COLUMN description varchar(2000),
    ADD COLUMN altitude_m  integer,
    ADD COLUMN season      varchar(200),
    ADD COLUMN phone       varchar(30),
    ADD CONSTRAINT chk_refuges_altitude CHECK (altitude_m IS NULL OR altitude_m BETWEEN 0 AND 5000);

-- I servizi da un elenco fisso (come gli enum delle altre tabelle): l'app ha un'icona e un'etichetta per ognuno
CREATE TABLE refuge_amenities (
    refuge_id uuid        NOT NULL,
    amenity   varchar(30) NOT NULL,

    CONSTRAINT pk_refuge_amenities PRIMARY KEY (refuge_id, amenity),
    CONSTRAINT fk_refuge_amenities_refuge FOREIGN KEY (refuge_id) REFERENCES refuges (id) ON DELETE CASCADE,
    CONSTRAINT chk_refuge_amenities_amenity CHECK (amenity IN ('DRINKING_WATER', 'RESTAURANT', 'KITCHEN', 'HEATING',
                                                                'SHOWER', 'ELECTRICITY', 'DOGS_ALLOWED', 'SHEETS_PROVIDED'))
);

-- Foto dei rifugi: caricate dal gestore del rifugio, viste da chi vede il rifugio
ALTER TABLE media_files DROP CONSTRAINT chk_media_files_entity_type;
ALTER TABLE media_files
    ADD CONSTRAINT chk_media_files_entity_type CHECK (entity_type IN ('TRAIL', 'HAZARD_REPORT', 'REFUGE'));
