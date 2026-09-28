-- Segnalazioni di pericolo. reporter_id è un UUID nell'entità (niente @ManyToOne User), la FK resta qui
CREATE TABLE hazard_reports (
    id          uuid PRIMARY KEY,
    reporter_id uuid        NOT NULL,
    trail_id    uuid,
    type        varchar(20) NOT NULL,
    severity    varchar(20) NOT NULL,
    description text,
    location    geometry(Point, 4326) NOT NULL,
    status      varchar(20) NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  uuid,

    CONSTRAINT fk_hazard_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT fk_hazard_reports_trail FOREIGN KEY (trail_id) REFERENCES trails (id),
    CONSTRAINT chk_hazard_reports_type CHECK (type IN ('LANDSLIDE', 'FALLEN_TREE', 'FLOODING', 'TRAIL_EROSION',
                                                       'SNOW_ICE', 'WILDLIFE', 'OTHER')),
    CONSTRAINT chk_hazard_reports_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_hazard_reports_status CHECK (status IN ('SUBMITTED', 'VERIFIED', 'REJECTED', 'RESOLVED'))
);

CREATE INDEX idx_hazard_reports_reporter ON hazard_reports (reporter_id);
CREATE INDEX idx_hazard_reports_trail ON hazard_reports (trail_id);
CREATE INDEX idx_hazard_reports_location ON hazard_reports USING GIST (location);
-- findActiveNearTrail: ST_DWithin(h.location::geography, t.path::geography, :buffer)
CREATE INDEX idx_hazard_reports_location_geog ON hazard_reports USING GIST ((location::geography));
