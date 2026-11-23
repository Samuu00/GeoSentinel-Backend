# ADR-0028: Percorso A→B con GraphHopper sull'estratto OSM della Calabria

- **Stato:** Accettato (sostituisce l'ADR-0027)
- **Data:** 2026-11-22

## Contesto

La rete in memoria dell'ADR-0027 conosceva solo i 351 sentieri del database, uniti se entro 15 m: circa 57 gruppi
non collegati. Quando partenza e arrivo stavano su gruppi diversi, l'app mostrava una linea dritta fino al sentiero
dell'arrivo. Serviva una rete con i collegamenti veri tra i sentieri. Una prima versione passava anche dalle strade;
il 22/11 si è deciso che il percorso usa solo i sentieri.

## Decisione

- **GraphHopper 10** (`israelhikingmap/graphhopper:10.2`) nel `docker-compose`, con il profilo `hike` di GraphHopper
  senza quote, sull'estratto OSM della Calabria (`tools/routing/build_extract.sh`: osmium sul `sud-latest.osm.pbf`
  di Geofabrik, lo stesso riquadro di `tools/osm-import`). Configurazione in `tools/routing/config.yml`, grafo nel
  volume `geosentinel_graphhopper_data`. Si tolgono solo le autostrade.
- **Stessa rotta** `GET /api/v1/trails/route` e stessa risposta (`path`, `lengthM`, `trailIds`): l'app non cambia.
  `RouteService` chiama la porta `RoutingEngine` (adattatore `GraphHopperRoutingEngine`, `POST /route`).
- **Solo sui sentieri del parco** (deciso il 22/11): niente strade. Il custom model di ogni richiesta dà priorità 0
  ai tratti che non fanno parte di un itinerario escursionistico OSM (`foot_network == MISSING`). I sentieri del parco
  vengono proprio dalle relazioni `route=hiking` (`tools/osm-import`), quindi gli incroci sono quelli veri di OSM.
  Partenza e arrivo devono stare **entro 1 km** dal punto in cui il motore li aggancia al sentiero, altrimenti 422
  `no-route`. Niente collegamento tra i sentieri: nessun percorso, mai linee dritte né strade.
- **Sentieri `CLOSED`:** a ogni richiesta, il loro tracciato allargato di 10 m (PostGIS, unione semplificata) va nel
  custom model come area con priorità 0. Il profilo non usa CH né LM, quindi il custom model per richiesta è
  ammesso. Conseguenza accettata: anche una strada che incrocia un sentiero chiuso è bloccata nell'arco che lo
  attraversa.
- **`trailIds`:** i sentieri aperti del database che il tracciato segue per almeno 50 m entro 20 m, ordinati lungo il
  percorso (PostGIS, `findTrailIdsAlong`).
- **Errori:** un 400 di GraphHopper (punto fuori dalla rete, punti non collegati) o un punto a più di 1 km dai
  sentieri è 422 `no-route`; rete, timeout
  (2 s connessione, 10 s lettura) e 5xx sono 503 `routing-unavailable` con `Retry-After: 5`.
- Le coordinate restano dentro la rete del compose (GraphHopper non è un servizio esterno) e non si loggano.

## Alternative scartate

- **Ponti in linea d'aria tra i gruppi di sentieri:** restano linee dritte dove i sentieri non ci sono.
- **Valhalla:** costing a piedi adatto, ma un'immagine e una configurazione più pesanti.
- **OSRM:** veloce, ma senza esclusioni per richiesta: i sentieri chiusi richiederebbero di ricostruire il grafo.

## Conseguenze

- Un servizio in più nel compose (circa 1 GB di heap). Al primo avvio GraphHopper costruisce il grafo.
- **Aggiornamento dell'estratto OSM:** a mano. Si rilancia `build_extract.sh`, poi si cancellano il container e il
  volume del grafo (comandi nello script). Va fatto anche quando si rifà l'import dei sentieri.
- Il dislivello non entra nel costo (niente SRTM). Si aggiunge `foot_elevation.json` quando servirà.
- Tolte la rete in memoria (`TrailNetwork`) e l'evento `TrailsChanged`.
