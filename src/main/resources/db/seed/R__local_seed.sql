-- =============================================================================
-- Seed di sviluppo, SOLO profilo local (spring.flyway.locations in application-local.properties).
-- Repeatable: Flyway lo riesegue quando il file cambia, ON CONFLICT DO NOTHING lo rende idempotente.
-- Password di tutti gli utenti: Password123!  (hash del DelegatingPasswordEncoder: serve il prefisso {bcrypt})
-- UUID fissi: 0…1-5 utenti, 1… sentieri, 2… rifugi, 3… squadre, 4… segnalazioni, 5… ticket, 6… SOS, 8… POI
-- =============================================================================

-- Utenti, uno per ruolo (+ un secondo escursionista). Email già in minuscolo (CHECK email = lower(email))
INSERT INTO users (id, email, password_hash, display_name, role, enabled)
VALUES ('00000000-0000-0000-0000-000000000001', 'admin@geosentinel.dev',
        '{bcrypt}$2a$10$bD.R59uzCn2e8vQXkjgBmOY74KBvcI58sdH0MOq4MHUnEeO5OpURK', 'Admin', 'ADMIN', TRUE),
       ('00000000-0000-0000-0000-000000000002', 'manager@geosentinel.dev',
        '{bcrypt}$2a$10$bD.R59uzCn2e8vQXkjgBmOY74KBvcI58sdH0MOq4MHUnEeO5OpURK', 'Gestore Parco Sila', 'PARK_MANAGER', TRUE),
       ('00000000-0000-0000-0000-000000000003', 'rescuer@geosentinel.dev',
        '{bcrypt}$2a$10$bD.R59uzCn2e8vQXkjgBmOY74KBvcI58sdH0MOq4MHUnEeO5OpURK', 'Operatore CNSAS', 'RESCUE_OPERATOR', TRUE),
       ('00000000-0000-0000-0000-000000000004', 'hiker@geosentinel.dev',
        '{bcrypt}$2a$10$bD.R59uzCn2e8vQXkjgBmOY74KBvcI58sdH0MOq4MHUnEeO5OpURK', 'Escursionista', 'HIKER', TRUE),
       ('00000000-0000-0000-0000-000000000005', 'hiker2@geosentinel.dev',
        '{bcrypt}$2a$10$bD.R59uzCn2e8vQXkjgBmOY74KBvcI58sdH0MOq4MHUnEeO5OpURK', 'Escursionista Due', 'HIKER', TRUE)
ON CONFLICT DO NOTHING;

-- Sentieri in Sila (LINESTRING(lon lat, ...)). length_m la calcola PostgreSQL
INSERT INTO trails (id, name, description, difficulty, status, path)
VALUES ('10000000-0000-0000-0000-000000000001', 'Sentiero delle Vette Silane',
        'Traversata panoramica da Camigliatello verso le vette della Sila Grande.', 'MODERATE', 'OPEN',
        ST_GeomFromText('LINESTRING(16.4852 39.2934, 16.4910 39.2980, 16.5020 39.3050, 16.5150 39.3100)', 4326)),
       ('10000000-0000-0000-0000-000000000002', 'Anello Monte Botte Donato',
        'Anello attorno alla cima più alta della Sila.', 'DIFFICULT', 'OPEN',
        ST_GeomFromText('LINESTRING(16.4520 39.2780, 16.4600 39.2850, 16.4710 39.2920, 16.4520 39.2780)', 4326))
ON CONFLICT DO NOTHING;

INSERT INTO pois (id, trail_id, name, type, location)
VALUES ('80000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        'Belvedere delle Vette', 'VIEWPOINT', ST_SetSRID(ST_MakePoint(16.5020, 39.3050), 4326)),
       ('80000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002',
        'Fonte Botte Donato', 'WATER_SOURCE', ST_SetSRID(ST_MakePoint(16.4600, 39.2850), 4326))
ON CONFLICT DO NOTHING;

-- Rifugio con capacità piccola (per provare il 409 capacity-exceeded), gestito dal PARK_MANAGER del seed
INSERT INTO refuges (id, name, location, capacity, manager_id)
VALUES ('20000000-0000-0000-0000-000000000001', 'Rifugio Monte Botte Donato',
        ST_SetSRID(ST_MakePoint(16.4520, 39.2780), 4326), 4, '00000000-0000-0000-0000-000000000002')
ON CONFLICT DO NOTHING;

INSERT INTO bookings (id, refuge_id, user_id, check_in, check_out, guests, status, idempotency_key)
VALUES ('21000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000004', CURRENT_DATE + 5, CURRENT_DATE + 7, 2, 'CONFIRMED',
        '21000000-0000-0000-0000-0000000000aa')
ON CONFLICT DO NOTHING;

INSERT INTO rescue_teams (id, name, call_sign, contact_number, status, base_location)
VALUES ('30000000-0000-0000-0000-000000000001', 'CNSAS Stazione Sila', 'AQUILA-1', '+390984118001', 'AVAILABLE',
        ST_SetSRID(ST_MakePoint(16.4500, 39.3300), 4326)),
       ('30000000-0000-0000-0000-000000000002', 'CNSAS Stazione Pollino', 'GRIFO-2', '+390981118002', 'AVAILABLE',
        ST_SetSRID(ST_MakePoint(16.1800, 39.9100), 4326))
ON CONFLICT DO NOTHING;

-- Segnalazione verificata sul primo sentiero, fatta dal secondo escursionista
INSERT INTO hazard_reports (id, reporter_id, trail_id, type, severity, description, location, status)
VALUES ('40000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000005',
        '10000000-0000-0000-0000-000000000001', 'LANDSLIDE', 'HIGH',
        'Cedimento del costone roccioso al km 3. Passaggio esposto.',
        ST_SetSRID(ST_MakePoint(16.4910, 39.2980), 4326), 'VERIFIED')
ON CONFLICT DO NOTHING;

-- Ticket nato dalla segnalazione: come MaintenanceTicket.forHazard, porta anche il sentiero
INSERT INTO maintenance_tickets (id, title, description, priority, status, trail_id, hazard_report_id)
VALUES ('50000000-0000-0000-0000-000000000001', 'Messa in sicurezza frana km 3',
        'Disgaggio massi e transenne provvisorie.', 'CRITICAL', 'OPEN',
        '10000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

-- SOS attivo mai notificato (notified_at NULL): SosNotificationRecovery lo notifica entro un minuto.
-- Sul secondo escursionista, così il primo può provare POST /sos (201) senza trovare un'allerta già attiva
INSERT INTO sos_alerts (id, hiker_id, location, location_updated_at, medical_notes, status)
VALUES ('60000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000005',
        ST_SetSRID(ST_MakePoint(16.5020, 39.3050), 4326), now(),
        'Sospetta distorsione alla caviglia.', 'ACTIVE')
ON CONFLICT DO NOTHING;

-- Niente media nel seed: una riga READY senza l'oggetto nel bucket darebbe un URL di download che risponde 404
