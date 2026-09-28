CREATE TABLE maintenance_tickets (
    id               uuid PRIMARY KEY,
    title            varchar(150) NOT NULL,
    description      text,
    priority         varchar(20)  NOT NULL,
    status           varchar(20)  NOT NULL,
    trail_id         uuid,
    refuge_id        uuid,
    hazard_report_id uuid,
    assigned_to      uuid,
    resolved_at      timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    updated_by       uuid,
    version          bigint       NOT NULL DEFAULT 0,

    CONSTRAINT fk_maintenance_trail FOREIGN KEY (trail_id) REFERENCES trails (id),
    CONSTRAINT fk_maintenance_refuge FOREIGN KEY (refuge_id) REFERENCES refuges (id),
    CONSTRAINT fk_maintenance_hazard FOREIGN KEY (hazard_report_id) REFERENCES hazard_reports (id),
    CONSTRAINT fk_maintenance_assigned FOREIGN KEY (assigned_to) REFERENCES users (id),
    CONSTRAINT chk_maintenance_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_maintenance_status CHECK (status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CANCELLED')),
    -- Almeno un target, non "esattamente uno": un ticket da segnalazione (MaintenanceTicket.forHazard)
    -- eredita anche il sentiero della segnalazione. L'"esattamente uno" della richiesta lo controlla il service
    CONSTRAINT chk_maintenance_target CHECK (num_nonnulls(trail_id, refuge_id, hazard_report_id) >= 1)
);

-- byTrail (findByTrailIdOrderByCreatedAtDesc); copre anche la FK trail_id
CREATE INDEX idx_maintenance_trail_created ON maintenance_tickets (trail_id, created_at);
CREATE INDEX idx_maintenance_refuge ON maintenance_tickets (refuge_id);
CREATE INDEX idx_maintenance_hazard ON maintenance_tickets (hazard_report_id);
CREATE INDEX idx_maintenance_assigned ON maintenance_tickets (assigned_to);
CREATE INDEX idx_maintenance_status_priority ON maintenance_tickets (status, priority);
