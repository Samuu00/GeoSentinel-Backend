#!/usr/bin/env bash
# Prepara l'estratto OpenStreetMap della Calabria usato da GraphHopper per calcolare i percorsi a piedi.
# Ritaglia sul riquadro della regione il file sud-latest.osm.pbf di Geofabrik (lo stesso usato da
# tools/osm-import, che viene riusato se è già stato scaricato). Si lancia dalla cartella del backend:
#   bash tools/routing/build_extract.sh
# Dopo, per ricostruire il grafo di GraphHopper con il nuovo estratto:
#   docker compose rm -sf graphhopper && docker volume rm geosentinel_graphhopper_data && docker compose up -d graphhopper

# Lo script si ferma al primo errore, su una variabile non definita o su un comando fallito in una pipe,
# e lavora sempre dalla propria cartella.
set -euo pipefail
cd "$(dirname "$0")"
CACHE=.cache
SUD=../osm-import/.cache/sud.osm.pbf
PBF_URL=${PBF_URL:-https://download.geofabrik.de/europe/italy/sud-latest.osm.pbf}
# Riquadro (ovest, sud, est, nord) uguale a quello di tools/osm-import: la Calabria con un po' di margine.
BBOX=15.55,37.85,17.30,40.20

mkdir -p "$CACHE" "$(dirname "$SUD")"
# Il file della macroregione si scarica solo se manca.
[ -f "$SUD" ] || curl -fL -o "$SUD" "$PBF_URL"
# Su Windows (Git Bash) Docker vuole il percorso del volume nella forma C:/..., che pwd -W restituisce.
DATA_DIR=$(cd .. && (pwd -W 2>/dev/null || pwd))
# osmium esegue il ritaglio dentro un container Debian usa e getta, così non va installato sul computer.
# MSYS_NO_PATHCONV impedisce a Git Bash di trasformare i percorsi del container in percorsi Windows.
MSYS_NO_PATHCONV=1 docker run --rm -v "$DATA_DIR:/tools" debian:bookworm-slim sh -c \
    "apt-get update -qq && apt-get install -y -qq osmium-tool >/dev/null && osmium extract --overwrite -b $BBOX /tools/osm-import/.cache/sud.osm.pbf -o /tools/routing/.cache/calabria.osm.pbf"
ls -lh "$CACHE/calabria.osm.pbf"
