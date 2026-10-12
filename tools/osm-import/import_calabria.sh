#!/usr/bin/env bash
# Dati veri della Calabria da OpenStreetMap nel seed locale (src/main/resources/db/seed/R__calabria_osm.sql).
# Uso, dalla root del backend con lo stack di sviluppo acceso:  bash tools/osm-import/import_calabria.sh
# Serve Docker: l'estratto di Geofabrik lo legge GDAL (ogr2ogr) in un container e lo carica nel database di appoggio
# osm_staging dello stesso Postgres; build_seed.sql ne ricava il seed. Niente Overpass: con le query su tutta la
# regione è spesso sovraccarico, e l'estratto rende l'import ripetibile.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=../..
CACHE=.cache
PBF_URL=${PBF_URL:-https://download.geofabrik.de/europe/italy/sud-latest.osm.pbf}
GDAL_IMAGE=ghcr.io/osgeo/gdal:alpine-small-3.11.4
PG_CONTAINER=${PG_CONTAINER:-geosentinel-postgres}
NETWORK=${NETWORK:-geosentinel-network}
# La Calabria con un po' di margine: il ritaglio esatto sul confine lo fa build_seed.sql
BBOX=(15.55 37.85 17.30 40.20)

set -a; . "$ROOT/.env"; set +a
mkdir -p "$CACHE"
[ -f "$CACHE/sud.osm.pbf" ] || curl -fL -o "$CACHE/sud.osm.pbf" "$PBF_URL"

psql_db() { docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" "$@"; }
psql_db -d postgres -q -c "DROP DATABASE IF EXISTS osm_staging" -c "CREATE DATABASE osm_staging"
psql_db -d osm_staging -q -c "CREATE EXTENSION postgis" -c "CREATE EXTENSION hstore"

# Su Windows (Git Bash) il volume vuole il percorso C:/...; MSYS_NO_PATHCONV evita che /data diventi un percorso Windows
DATA_DIR=$(cd "$CACHE" && (pwd -W 2>/dev/null || pwd))
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
SEED="$ROOT/src/main/resources/db/seed/R__calabria_osm.sql"
psql_db -d osm_staging -At -q < build_seed.sql > "$SEED"
# I rifugi con i tag che portano a una foto (per photos.py): Wikimedia Commons, immagine, Wikidata
psql_db -d osm_staging -At -q -c "SELECT json_agg(json_build_object('id', md5('osm/' || osm_key)::uuid, 'name', name, 'lon', ST_X(geom), 'lat', ST_Y(geom), 'commons', t -> 'wikimedia_commons', 'image', t -> 'image', 'wikidata', t -> 'wikidata') ORDER BY name) FROM features WHERE t -> 'tourism' IN ('alpine_hut', 'wilderness_hut') AND name IS NOT NULL" > "$CACHE/refuges.json"

grep -c '^INSERT INTO trails' "$SEED" | sed 's/^/Sentieri: /'
grep -c '^INSERT INTO pois' "$SEED" | sed 's/^/POI: /'
grep -c '^INSERT INTO refuges' "$SEED" | sed 's/^/Rifugi: /'
echo "Fatto: $SEED (lo applica Flyway al prossimo avvio con il profilo local)"
