-- =============================================================================
-- Seed di sviluppo, SOLO profilo local (spring.flyway.locations in application-local.properties).
-- Repeatable: Flyway lo riesegue quando il file cambia, ON CONFLICT DO NOTHING lo rende idempotente.
-- Password di tutti gli utenti: Password123!  (hash del DelegatingPasswordEncoder: serve il prefisso {bcrypt})
-- Solo gli utenti di sviluppo (UUID fissi 0...1-5): sentieri, rifugi e POI veri arrivano da R__calabria_osm.sql
-- (OpenStreetMap, tools/osm-import). Niente segnalazioni, ticket, SOS, prenotazioni o squadre inventate.
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
