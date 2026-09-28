-- Sentieri, POI, rifugi e prenotazioni. Ogni geometria ha due indici GiST:
-- sulla colonna (ST_Contains, bbox) e su (col::geography) per ST_DWithin in metri (D6)

-- -----------------------------------------------------------------------------
-- Sentieri
-- -----------------------------------------------------------------------------
CREATE TABLE trails (
    id          uuid PRIMARY KEY,
    name        varchar(150) NOT NULL,
    description text,
    difficulty  varchar(20)  NOT NULL,
    status      varchar(20)  NOT NULL,
    path        geometry(LineString, 4326) NOT NULL,
    -- calcolata da PostgreSQL: Trail.lengthM è @Generated e non la scrive mai
    length_m    double precision GENERATED ALWAYS AS (ST_Length(path::geography)) STORED,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  uuid,
    version     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT chk_trails_difficulty CHECK (difficulty IN ('EASY', 'MODERATE', 'DIFFICULT', 'EXPERT')),
    CONSTRAINT chk_trails_status CHECK (status IN ('OPEN', 'WARNING', 'CLOSED'))
);

CREATE INDEX idx_trails_path ON trails USING GIST (path);
CREATE INDEX idx_trails_path_geog ON trails USING GIST ((path::geography));

-- -----------------------------------------------------------------------------
-- POI (Poi estende AuditableEntity: servono le colonne di audit)
-- -----------------------------------------------------------------------------
CREATE TABLE pois (
    id         uuid PRIMARY KEY,
    trail_id   uuid,
    name       varchar(100) NOT NULL,
    type       varchar(20)  NOT NULL,
    location   geometry(Point, 4326) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid,

    CONSTRAINT fk_pois_trail FOREIGN KEY (trail_id) REFERENCES trails (id),
    CONSTRAINT chk_pois_type CHECK (type IN ('SHELTER', 'WATER_SOURCE', 'VIEWPOINT', 'DANGER_ZONE',
                                             'EQUIPMENT_POINT', 'INFORMATION'))
);

CREATE INDEX idx_pois_trail ON pois (trail_id);
CREATE INDEX idx_pois_location ON pois USING GIST (location);
CREATE INDEX idx_pois_location_geog ON pois USING GIST ((location::geography));

-- -----------------------------------------------------------------------------
-- Rifugi
-- -----------------------------------------------------------------------------
CREATE TABLE refuges (
    id         uuid PRIMARY KEY,
    name       varchar(120) NOT NULL,
    location   geometry(Point, 4326) NOT NULL,
    capacity   integer      NOT NULL,
    manager_id uuid         NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by uuid,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    updated_by uuid,

    CONSTRAINT fk_refuges_manager FOREIGN KEY (manager_id) REFERENCES users (id),
    CONSTRAINT chk_refuges_capacity CHECK (capacity > 0)
);

CREATE INDEX idx_refuges_manager ON refuges (manager_id);
CREATE INDEX idx_refuges_location ON refuges USING GIST (location);
CREATE INDEX idx_refuges_location_geog ON refuges USING GIST ((location::geography));

-- -----------------------------------------------------------------------------
-- Prenotazioni
-- -----------------------------------------------------------------------------
CREATE TABLE bookings (
    id              uuid PRIMARY KEY,
    refuge_id       uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    check_in        date        NOT NULL,
    check_out       date        NOT NULL,
    guests          integer     NOT NULL,
    status          varchar(20) NOT NULL,
    -- D12: un retry con la stessa chiave restituisce la prenotazione già creata
    idempotency_key uuid        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid,

    CONSTRAINT fk_bookings_refuge FOREIGN KEY (refuge_id) REFERENCES refuges (id),
    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_bookings_dates CHECK (check_out > check_in),
    CONSTRAINT chk_bookings_guests CHECK (guests > 0),
    CONSTRAINT chk_bookings_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    -- copre anche l'indice sulla FK user_id (colonna iniziale)
    CONSTRAINT uk_bookings_user_idempotency UNIQUE (user_id, idempotency_key)
);

-- maxNightlyOccupancy e findActiveOverlappingBookings; copre anche la FK refuge_id
CREATE INDEX idx_bookings_refuge_dates ON bookings (refuge_id, check_in, check_out);
