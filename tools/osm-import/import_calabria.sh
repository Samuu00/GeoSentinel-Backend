#!/usr/bin/env bash
# Importa i dati reali della Calabria da OpenStreetMap e genera il seed locale
# src/main/resources/db/seed/R__calabria_osm.sql (sentieri, punti di interesse e rifugi).
# Si lancia dalla cartella del backend, con lo stack di sviluppo acceso:
#   bash tools/osm-import/import_calabria.sh
# Serve Docker: l'estratto di Geofabrik viene letto da GDAL (ogr2ogr) in un container e caricato nel database
# di appoggio osm_staging, creato nello stesso Postgres di sviluppo; poi build_seed.sql ne ricava il seed.
# Non si usa l'API Overpass: con query su tutta la regione è spesso sovraccarica, mentre l'estratto rende
# l'import ripetibile.

# Lo script si ferma al primo errore e lavora sempre dalla propria cartella.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=../..
CACHE=.cache
PBF_URL=${PBF_URL:-https://download.geofabrik.de/europe/italy/sud-latest.osm.pbf}
# Immagine di GDAL a versione fissa; container e rete di Postgres si possono cambiare da variabile d'ambiente.
GDAL_IMAGE=ghcr.io/osgeo/gdal:alpine-small-3.11.4
PG_CONTAINER=${PG_CONTAINER:-geosentinel-postgres}
NETWORK=${NETWORK:-geosentinel-network}
# Riquadro (ovest, sud, est, nord) della Calabria con un po' di margine: il ritaglio esatto sul confine
# regionale lo fa build_seed.sql.
BBOX=(15.55 37.85 17.30 40.20)

# Legge utente e password del database dal .env del backend; l'estratto si scarica solo se manca.
set -a; . "$ROOT/.env"; set +a
mkdir -p "$CACHE"
[ -f "$CACHE/sud.osm.pbf" ] || curl -fL -o "$CACHE/sud.osm.pbf" "$PBF_URL"

# Esegue psql dentro il container di Postgres, fermandosi al primo errore SQL. Il database di appoggio
# viene ricreato da zero a ogni esecuzione, con le estensioni PostGIS e hstore.
psql_db() { docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" "$@"; }
psql_db -d postgres -q -c "DROP DATABASE IF EXISTS osm_staging" -c "CREATE DATABASE osm_staging"
psql_db -d osm_staging -q -c "CREATE EXTENSION postgis" -c "CREATE EXTENSION hstore"

# Su Windows (Git Bash) Docker vuole il percorso del volume nella forma C:/...; MSYS_NO_PATHCONV evita che
# /data venga trasformato in un percorso Windows.
DATA_DIR=$(cd "$CACHE" && (pwd -W 2>/dev/null || pwd))
# Carica un livello dell'estratto in una tabella di osm_staging, limitandosi al riquadro BBOX.
# Gli argomenti aggiuntivi scelgono il livello (points, multilinestrings, multipolygons), la tabella e il filtro.
ogr() {
    MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" -e PGPASSWORD="$POSTGRES_PASSWORD" -v "$DATA_DIR:/data:ro" \
        "$GDAL_IMAGE" ogr2ogr -f PostgreSQL "PG:host=$PG_CONTAINER dbname=osm_staging user=$POSTGRES_USER" \
        /data/sud.osm.pbf "$@" -spat "${BBOX[@]}" -lco GEOMETRY_NAME=geom -overwrite
}
echo "Punti (rifugi, ripari, sorgenti, belvedere)..."
ogr points -nln osm_points -where "other_tags LIKE '%\"tourism\"=>\"alpine_hut\"%' OR other_tags LIKE '%\"tourism\"=>\"wilderness_hut\"%' OR other_tags LIKE '%\"amenity\"=>\"shelter\"%' OR other_tags LIKE '%\"natural\"=>\"spring\"%' OR other_tags LIKE '%\"tourism\"=>\"viewpoint\"%'"
echo "Sentieri (relazioni route=hiking)..."
ogr multilinestrings -nln osm_routes -where "type = 'route' AND other_tags LIKE '%\"route\"=>\"hiking\"%'"
echo "Aree (confine della Calabria, rifugi mappati come edificio)..."
ogr multipolygons -nln osm_areas -where "(name = 'Calabria' AND admin_level = '4') OR tourism IN ('alpine_hut', 'wilderness_hut') OR amenity = 'shelter'"

echo "Seed..."
# Genera il seed SQL eseguendo build_seed.sql sul database di appoggio.
SEED="$ROOT/src/main/resources/db/seed/R__calabria_osm.sql"
psql_db -d osm_staging -At -q < build_seed.sql > "$SEED"
# Salva in .cache/refuges.json i rifugi con i tag che possono portare a una foto (Wikimedia Commons,
# immagine, Wikidata); il file viene poi letto da photos.py. L'id è calcolato come in build_seed.sql.
psql_db -d osm_staging -At -q -c "SELECT json_agg(json_build_object('id', md5('osm/' || osm_key)::uuid, 'name', name, 'lon', ST_X(geom), 'lat', ST_Y(geom), 'commons', t -> 'wikimedia_commons', 'image', t -> 'image', 'wikidata', t -> 'wikidata') ORDER BY name) FROM features WHERE t -> 'tourism' IN ('alpine_hut', 'wilderness_hut') AND name IS NOT NULL" > "$CACHE/refuges.json"

# Riepilogo: quanti sentieri, POI e rifugi sono finiti nel seed.
grep -c '^INSERT INTO trails' "$SEED" | sed 's/^/Sentieri: /'
grep -c '^INSERT INTO pois' "$SEED" | sed 's/^/POI: /'
grep -c '^INSERT INTO refuges' "$SEED" | sed 's/^/Rifugi: /'
echo "Fatto: $SEED (lo applica Flyway al prossimo avvio con il profilo local)"
