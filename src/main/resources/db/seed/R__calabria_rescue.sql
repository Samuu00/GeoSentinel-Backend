-- =============================================================================
-- Le stazioni vere del Soccorso Alpino e Speleologico Calabria (SASC, servizio regionale del CNSAS), SOLO profilo
-- local. Fonte: https://sasc.it/mappa-delle-stazioni/ e le pagine delle stazioni (consultate il 12/10/2026):
-- quattro stazioni in due delegazioni, Nord (Pollino, Sila) e Sud (Aspromonte, Catanzaro).
-- Il sito non pubblica né la sede né il telefono delle stazioni (dice di chiamare il 112 e chiedere il Soccorso
-- Alpino), quindi:
--   - base_location: una località della zona di competenza, indicativa, non la sede vera;
--   - call_sign: un codice ricavato dal nome, non un nominativo radio ufficiale;
--   - contact_number: il 112, l'unico numero indicato.
-- Idempotente: ON CONFLICT DO NOTHING (anche sul nome, che è unico).
-- =============================================================================

INSERT INTO rescue_teams (id, name, call_sign, contact_number, status, base_location)
VALUES
    -- Delegazione Nord. Pollino e Tirreno settentrionale (70 comuni): Castrovillari
    ('30000000-0000-0000-0000-000000000001', 'SASC Stazione Pollino', 'SASC-POLLINO', '112', 'AVAILABLE',
     ST_SetSRID(ST_MakePoint(16.2017, 39.8158), 4326)),
    -- Delegazione Nord. Dalla Sila Greca alla Sila Piccola (107 comuni): Camigliatello Silano
    ('30000000-0000-0000-0000-000000000002', 'SASC Stazione Sila', 'SASC-SILA', '112', 'AVAILABLE',
     ST_SetSRID(ST_MakePoint(16.4497, 39.3392), 4326)),
    -- Delegazione Sud. Aspromonte e provincia di Reggio Calabria (97 comuni): Gambarie
    ('30000000-0000-0000-0000-000000000003', 'SASC Stazione Aspromonte', 'SASC-ASPROMONTE', '112', 'AVAILABLE',
     ST_SetSRID(ST_MakePoint(15.8333, 38.1667), 4326)),
    -- Delegazione Sud. Catanzaro, Crotone e Vibo Valentia (130 comuni): Catanzaro
    ('30000000-0000-0000-0000-000000000004', 'SASC Stazione Catanzaro', 'SASC-CATANZARO', '112', 'AVAILABLE',
     ST_SetSRID(ST_MakePoint(16.5875, 38.9100), 4326))
ON CONFLICT DO NOTHING;
