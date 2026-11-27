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

-- Prezzo base di prova per notte (M10, solo sviluppo): da 25,00 a 60,00 € a persona, a passi di 0,50 €. È pseudo-casuale
-- ma stabile (ricavato dall'id), così a ogni riesecuzione del seed non cambia. Solo sui rifugi senza prezzo: quello
-- impostato da un gestore resta. Il seed OSM (R__calabria_osm.sql) non ha prezzi, che su rifugi veri sarebbero inventati.
UPDATE refuges
SET price_per_night_cents = 2500 + (abs(hashtext(id::text)) % 71) * 50
WHERE price_per_night_cents IS NULL;
