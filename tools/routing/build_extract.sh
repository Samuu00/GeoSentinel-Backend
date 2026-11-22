#!/usr/bin/env bash
# Estratto OSM della Calabria per GraphHopper (ADR-0028): ritaglia sud-latest.osm.pbf di Geofabrik (lo stesso di
# tools/osm-import, riusato se c'è) sul riquadro della regione. Uso, dalla root del backend:
#   bash tools/routing/build_extract.sh
# Poi, per ricostruire il grafo con l'estratto nuovo:
#   docker compose rm -sf graphhopper && docker volume rm geosentinel_graphhopper_data && docker compose up -d graphhopper
set -euo pipefail
cd "$(dirname "$0")"
CACHE=.cache
SUD=../osm-import/.cache/sud.osm.pbf
PBF_URL=${PBF_URL:-https://download.geofabrik.de/europe/italy/sud-latest.osm.pbf}
# Come BBOX di tools/osm-import: la Calabria con un po' di margine
BBOX=15.55,37.85,17.30,40.20

mkdir -p "$CACHE" "$(dirname "$SUD")"
[ -f "$SUD" ] || curl -fL -o "$SUD" "$PBF_URL"
# Su Windows (Git Bash) il volume vuole il percorso C:/...
DATA_DIR=$(cd .. && (pwd -W 2>/dev/null || pwd))
MSYS_NO_PATHCONV=1 docker run --rm -v "$DATA_DIR:/tools" debian:bookworm-slim sh -c \
    "apt-get update -qq && apt-get install -y -qq osmium-tool >/dev/null && osmium extract --overwrite -b $BBOX /tools/osm-import/.cache/sud.osm.pbf -o /tools/routing/.cache/calabria.osm.pbf"
ls -lh "$CACHE/calabria.osm.pbf"
