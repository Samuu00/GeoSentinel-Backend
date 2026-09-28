-- -----------------------------------------------------------------------------
-- Squadre di soccorso (niente version: le scritture passano da lock pessimistici)
-- -----------------------------------------------------------------------------
CREATE TABLE rescue_teams (
    id             uuid PRIMARY KEY,
    name           varchar(100) NOT NULL,
    call_sign      varchar(32)  NOT NULL,
    contact_number varchar(30)  NOT NULL,
    status         varchar(20)  NOT NULL,
    base_location  geometry(Point, 4326) NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    updated_by     uuid,

    CONSTRAINT uk_rescue_teams_name UNIQUE (name),
    CONSTRAINT chk_rescue_teams_status CHECK (status IN ('AVAILABLE', 'ON_MISSION', 'OFF_DUTY'))
);

CREATE INDEX idx_rescue_teams_location ON rescue_teams USING GIST (base_location);
-- findAvailableNearby: ST_DWithin in metri
CREATE INDEX idx_rescue_teams_location_geog ON rescue_teams USING GIST ((base_location::geography));

-- -----------------------------------------------------------------------------
-- Allerte SOS (niente version, come sopra)
-- notified_at e anonymized_at non sono mappati nell'entità: li scrivono solo query dedicate
-- -----------------------------------------------------------------------------
CREATE TABLE sos_alerts (
    id                  uuid PRIMARY KEY,
    hiker_id            uuid        NOT NULL,
    idempotency_key     uuid,
    location            geometry(Point, 4326) NOT NULL,
    location_updated_at timestamptz NOT NULL,
    medical_notes       text,
    status              varchar(20) NOT NULL,
    assigned_team_id    uuid,
    operator_id         uuid,
    closed_at           timestamptz,
    notified_at         timestamptz,
    anonymized_at       timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          uuid,

    CONSTRAINT fk_sos_alerts_hiker FOREIGN KEY (hiker_id) REFERENCES users (id),
    CONSTRAINT fk_sos_alerts_team FOREIGN KEY (assigned_team_id) REFERENCES rescue_teams (id),
    CONSTRAINT fk_sos_alerts_operator FOREIGN KEY (operator_id) REFERENCES users (id),
    CONSTRAINT chk_sos_alerts_status CHECK (status IN ('ACTIVE', 'DISPATCHED', 'RESOLVED', 'CANCELLED')),
    -- D12: i NULL non collidono (Idempotency-Key facoltativa); copre anche la FK hiker_id
    CONSTRAINT uk_sos_alerts_hiker_idempotency UNIQUE (hiker_id, idempotency_key)
);

-- Al più un'allerta attiva per escursionista: rete di sicurezza dell'advisory lock di raiseSos
CREATE UNIQUE INDEX uk_sos_alerts_one_active ON sos_alerts (hiker_id) WHERE status IN ('ACTIVE', 'DISPATCHED');
-- Dashboard delle allerte attive
CREATE INDEX idx_sos_alerts_active_created ON sos_alerts (created_at) WHERE status IN ('ACTIVE', 'DISPATCHED');
-- SosNotificationRecovery
CREATE INDEX idx_sos_alerts_unnotified ON sos_alerts (created_at) WHERE notified_at IS NULL;
-- SosDataRetentionJob
CREATE INDEX idx_sos_alerts_retention ON sos_alerts (closed_at) WHERE anonymized_at IS NULL;
CREATE INDEX idx_sos_alerts_team ON sos_alerts (assigned_team_id);
CREATE INDEX idx_sos_alerts_operator ON sos_alerts (operator_id);
-- findNearbyActive: ST_DWithin in metri
CREATE INDEX idx_sos_alerts_location_geog ON sos_alerts USING GIST ((location::geography));
