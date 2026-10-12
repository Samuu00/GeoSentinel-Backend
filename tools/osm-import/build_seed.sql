-- Dal database di appoggio osm_staging (caricato da import_calabria.sh con ogr2ogr) al seed SQL dei dati veri della
-- Calabria. Stampa solo istruzioni SQL (psql -At -q): l'output diventa src/main/resources/db/seed/R__calabria_osm.sql.
-- UUID deterministici dall'id OSM (md5), così un nuovo import aggiorna le stesse righe invece di duplicarle.
\set ON_ERROR_STOP on

-- Il confine della regione: tutto quello che non lo tocca resta fuori
DROP TABLE IF EXISTS calabria;
CREATE TABLE calabria AS
SELECT ST_Union(geom) AS geom FROM osm_areas WHERE name = 'Calabria' AND admin_level = '4';

-- Punti e aree (rifugi mappati come edificio) in un'unica tabella, con tutti i tag
DROP TABLE IF EXISTS features;
CREATE TABLE features AS
SELECT 'node/' || p.osm_id AS osm_key, p.name, coalesce(p.other_tags, '')::hstore AS t, p.geom
FROM osm_points p
UNION ALL
SELECT CASE WHEN a.osm_way_id IS NOT NULL THEN 'way/' || a.osm_way_id ELSE 'relation/' || a.osm_id END,
       a.name,
       coalesce(a.other_tags, '')::hstore
           || CASE WHEN a.tourism IS NOT NULL THEN hstore('tourism', a.tourism) ELSE ''::hstore END
           || CASE WHEN a.amenity IS NOT NULL THEN hstore('amenity', a.amenity) ELSE ''::hstore END,
       ST_PointOnSurface(a.geom)
FROM osm_areas a
WHERE a.tourism IN ('alpine_hut', 'wilderness_hut') OR a.amenity = 'shelter';
DELETE FROM features f USING calabria c WHERE NOT ST_Within(f.geom, c.geom);
-- Lo stesso rifugio mappato due volte (punto ed edificio): si tiene uno solo per nome entro 300 m
DELETE FROM features a USING features b
WHERE a.t -> 'tourism' IN ('alpine_hut', 'wilderness_hut') AND b.t -> 'tourism' IN ('alpine_hut', 'wilderness_hut')
  AND lower(a.name) = lower(b.name) AND a.osm_key > b.osm_key AND ST_DWithin(a.geom::geography, b.geom::geography, 300);

-- Sentieri: le relazioni route=hiking che toccano la Calabria. Le vie della relazione si uniscono (ST_LineMerge);
-- dove restano pezzi staccati si tiene il più lungo (il database vuole una LineString). Semplificati a circa 3 m.
DROP TABLE IF EXISTS trails_out;
CREATE TABLE trails_out AS
WITH r AS (
    SELECT o.osm_id, o.name, coalesce(o.other_tags, '')::hstore AS t, o.geom
    FROM osm_routes o, calabria c
    WHERE ST_Intersects(o.geom, c.geom)
),
parts AS (
    SELECT r.osm_id, (ST_Dump(ST_LineMerge(r.geom))).geom AS part FROM r
),
longest AS (
    SELECT DISTINCT ON (osm_id) osm_id, part FROM parts ORDER BY osm_id, ST_Length(part::geography) DESC
)
SELECT md5('osm/relation/' || r.osm_id)::uuid AS id,
       -- senza nome (es. le tappe del Sentiero Italia, tutte "SI") il segnavia e, se ci sono, partenza e arrivo
       left(coalesce(r.name, r.t -> 'name:it',
                     'Sentiero ' || (r.t -> 'ref') || coalesce(': ' || (r.t -> 'from') || ' – ' || (r.t -> 'to'), '')), 150) AS name,
       r.t,
       ST_SimplifyPreserveTopology(l.part, 0.00003) AS path
FROM r JOIN longest l USING (osm_id)
WHERE coalesce(r.name, r.t -> 'name:it', r.t -> 'ref') IS NOT NULL
  AND ST_Length(l.part::geography) >= 300;

-- La difficoltà: scala CAI (T, E, EE, EEA) o SAC; senza nessuna delle due "Media", detto nella descrizione
ALTER TABLE trails_out ADD COLUMN difficulty text, ADD COLUMN estimated boolean;
UPDATE trails_out SET
    difficulty = CASE
        WHEN upper(t -> 'cai_scale') = 'T' THEN 'EASY'
        WHEN upper(t -> 'cai_scale') = 'E' THEN 'MODERATE'
        WHEN upper(t -> 'cai_scale') = 'EE' THEN 'DIFFICULT'
        WHEN upper(t -> 'cai_scale') LIKE 'EEA%' THEN 'EXPERT'
        WHEN t -> 'sac_scale' = 'hiking' THEN 'EASY'
        WHEN t -> 'sac_scale' = 'mountain_hiking' THEN 'MODERATE'
        WHEN t -> 'sac_scale' = 'demanding_mountain_hiking' THEN 'DIFFICULT'
        WHEN t -> 'sac_scale' LIKE '%alpine_hiking' THEN 'EXPERT'
        ELSE 'MODERATE'
    END,
    estimated = NOT (t ? 'cai_scale' OR t ? 'sac_scale');

\echo '-- ============================================================================='
\echo '-- Dati veri della Calabria da OpenStreetMap: sentieri (relazioni route=hiking), rifugi e bivacchi,'
\echo '-- ripari, sorgenti e belvedere. GENERATO da tools/osm-import/import_calabria.sh: non modificare a mano.'
\echo '-- Dati © OpenStreetMap contributors, licenza ODbL (https://www.openstreetmap.org/copyright).'
\echo '-- Solo profilo local, come R__local_seed.sql. Idempotente: ON CONFLICT DO NOTHING.'
\echo '-- ============================================================================='

SELECT format(
    'INSERT INTO trails (id, name, description, difficulty, status, path) VALUES (%L, %L, %L, %L, ''OPEN'', ST_GeomFromText(%L, 4326)) ON CONFLICT DO NOTHING;',
    id, name,
    concat_ws(E'\n',
        CASE WHEN t ? 'from' AND t ? 'to' THEN 'Da ' || (t -> 'from') || ' a ' || (t -> 'to') END,
        CASE WHEN t ? 'ref' THEN 'Segnavia ' || (t -> 'ref') END,
        CASE WHEN t ? 'cai_scale' THEN 'Scala CAI: ' || upper(t -> 'cai_scale') END,
        CASE WHEN estimated THEN 'Difficoltà non indicata su OpenStreetMap: stimata.' END,
        coalesce(t -> 'description:it', t -> 'description'),
        CASE WHEN t ? 'operator' THEN 'Gestione: ' || (t -> 'operator') END,
        'Dati © OpenStreetMap contributors (ODbL).'),
    difficulty,
    ST_AsText(path, 6))
FROM trails_out
ORDER BY name, id;

-- POI: ripari, sorgenti e belvedere (i rifugi veri e propri vanno in refuges)
SELECT format(
    'INSERT INTO pois (id, name, type, location) VALUES (%L, %L, %L, ST_SetSRID(ST_MakePoint(%s, %s), 4326)) ON CONFLICT DO NOTHING;',
    md5('osm/' || osm_key)::uuid,
    left(coalesce(name, CASE type WHEN 'SHELTER' THEN 'Riparo' WHEN 'WATER_SOURCE' THEN 'Sorgente' ELSE 'Belvedere' END), 100),
    type,
    round(ST_X(geom)::numeric, 6), round(ST_Y(geom)::numeric, 6))
FROM (
    SELECT f.*,
           CASE
               WHEN t -> 'amenity' = 'shelter' OR t -> 'tourism' IN ('alpine_hut', 'wilderness_hut') THEN 'SHELTER'
               WHEN t -> 'natural' = 'spring' THEN 'WATER_SOURCE'
               WHEN t -> 'tourism' = 'viewpoint' THEN 'VIEWPOINT'
           END AS type
    FROM features f
    -- i rifugi senza nome non si possono prenotare: sulla mappa come ripari
    WHERE NOT coalesce(t -> 'tourism', '') IN ('alpine_hut', 'wilderness_hut') OR name IS NULL
) p
WHERE type IS NOT NULL
ORDER BY type, name, osm_key;

-- Rifugi e bivacchi con un nome, gestiti dal PARK_MANAGER di sviluppo. Posti letto dal tag capacity; dove manca sono stimati
-- (20 per un rifugio, 6 per un bivacco) e la descrizione lo dice
SELECT format(
    'INSERT INTO refuges (id, name, location, capacity, manager_id, description, altitude_m, season, phone) VALUES (%L, %L, ST_SetSRID(ST_MakePoint(%s, %s), 4326), %s, ''00000000-0000-0000-0000-000000000002'', %L, %s, %L, %L) ON CONFLICT DO NOTHING;',
    md5('osm/' || osm_key)::uuid,
    left(name, 120),
    round(ST_X(geom)::numeric, 6), round(ST_Y(geom)::numeric, 6),
    coalesce(beds, CASE WHEN hut = 'wilderness_hut' THEN 6 ELSE 20 END),
    left(concat_ws(E'\n',
        coalesce(t -> 'description:it', t -> 'description'),
        CASE WHEN hut = 'wilderness_hut' THEN 'Bivacco incustodito.' END,
        CASE WHEN t ? 'operator' THEN 'Gestione: ' || (t -> 'operator') END,
        CASE WHEN beds IS NULL THEN 'Posti letto stimati: il numero non è indicato su OpenStreetMap.' END,
        'Dati © OpenStreetMap contributors (ODbL).'), 1900),
    coalesce(ele::text, 'NULL'),
    left(t -> 'opening_hours', 200),
    left(split_part(coalesce(t -> 'phone', t -> 'contact:phone'), ';', 1), 30))
FROM (
    SELECT f.*, t -> 'tourism' AS hut,
           -- CASE e non least(): least(NULL, 1000) in PostgreSQL vale 1000, e la stima non scatterebbe mai
           CASE WHEN substring(t -> 'capacity' FROM '\d+')::int BETWEEN 1 AND 1000 THEN substring(t -> 'capacity' FROM '\d+')::int END AS beds,
           CASE WHEN substring(t -> 'ele' FROM '^\d+')::int BETWEEN 0 AND 5000 THEN substring(t -> 'ele' FROM '^\d+')::int END AS ele
    FROM features f
    WHERE t -> 'tourism' IN ('alpine_hut', 'wilderness_hut') AND name IS NOT NULL
) h
ORDER BY name, osm_key;

-- Servizi dei rifugi, dai tag che OSM usa davvero
SELECT format('INSERT INTO refuge_amenities (refuge_id, amenity) VALUES (%L, %L) ON CONFLICT DO NOTHING;', id, amenity)
FROM (
    SELECT md5('osm/' || osm_key)::uuid AS id, unnest(array_remove(ARRAY[
        CASE WHEN t -> 'drinking_water' = 'yes' THEN 'DRINKING_WATER' END,
        CASE WHEN t -> 'food' = 'yes' OR t -> 'amenity' = 'restaurant' THEN 'RESTAURANT' END,
        CASE WHEN t -> 'kitchen' = 'yes' THEN 'KITCHEN' END,
        CASE WHEN t -> 'fireplace' = 'yes' OR t -> 'heating' = 'yes' OR t ? 'stove' THEN 'HEATING' END,
        CASE WHEN t -> 'shower' IN ('yes', 'hot') THEN 'SHOWER' END,
        CASE WHEN t -> 'electricity' = 'yes' OR t -> 'power_supply' = 'yes' THEN 'ELECTRICITY' END,
        CASE WHEN t -> 'dog' = 'yes' THEN 'DOGS_ALLOWED' END
    ], NULL)) AS amenity
    FROM features
    WHERE t -> 'tourism' IN ('alpine_hut', 'wilderness_hut') AND name IS NOT NULL
) a
ORDER BY id, amenity;

\echo '-- Ogni POI importato si lega al sentiero più vicino entro 50 m (se c''è)'
\echo 'UPDATE pois p SET trail_id = (SELECT t.id FROM trails t WHERE ST_DWithin(t.path::geography, p.location::geography, 50) ORDER BY ST_Distance(t.path::geography, p.location::geography) LIMIT 1) WHERE p.trail_id IS NULL;'
