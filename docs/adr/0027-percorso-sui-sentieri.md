# ADR-0027: Percorso A→B sui sentieri, calcolato in memoria con JTS

- **Stato:** Accettato
- **Data:** 2026-11-21

## Contesto

L'app vuole indicare un percorso come Google Maps: partenza (la posizione dell'utente o un altro punto), destinazione,
e il tracciato evidenziato sui sentieri. Il backend non aveva nessun calcolo di percorsi. La rete sono i 351 sentieri
importati da OpenStreetMap (`tools/osm-import`), circa 67k vertici. Misura del 21/11 sui sentieri non chiusi: a 5 m
di tolleranza ci sono 58 gruppi di sentieri collegati (il più grande ne ha 214), a 15 m 57, a 50 m 52.

## Decisione

- `GET /api/v1/trails/route?fromLon&fromLat&toLon&toLat` (`findRoute`), pubblica come il resto del catalogo. Risponde
  `path` (GeoJSON `[lon, lat]`), `lengthM` e `trailIds` in ordine (tracciato semplificato a ~5 m); 422 `no-route` se un punto è a più di 50 km da ogni
  sentiero aperto o i due punti stanno su sentieri non collegati.
- Partenza e arrivo si agganciano al vertice più vicino di ogni gruppo di sentieri collegati entro 50 km (il più vicino
  in assoluto può stare su un gruppo isolato); la linea d'aria fino al sentiero entra nel costo.
- Rete in memoria (`TrailNetwork`): un nodo per vertice, archi lungo i sentieri e archi di giunzione tra vertici di
  sentieri diversi entro 15 m; Dijkstra sulla distanza. I sentieri `CLOSED` sono esclusi.
- `RouteService` costruisce la rete alla prima richiesta e la butta dopo il commit di ogni creazione, modifica o cambio
  di stato di un sentiero (`TrailsChanged`, `@TransactionalEventListener`). Costruzione e invalidazione sono sincronizzate.
- Partenza e arrivo sono posizioni degli utenti: non si loggano.

## Alternative scartate

- **pgRouting:** nuova immagine del database (compose, produzione, Testcontainers), una migrazione e la topologia da
  ricostruire a ogni cambio. Per 67k vertici il grafo in memoria basta, si testa senza Docker e non tocca lo schema.
- **Servizio esterno** (OpenRouteService, GraphHopper): la posizione dell'utente uscirebbe dal backend, serve una chiave
  e il percorso userebbe sentieri che il parco non gestisce né chiude.

## Conseguenze

- Il dislivello non c'è: il server non ha le quote. Si aggiunge quando ci saranno.
- Con più istanze del backend ognuna ha la sua rete; l'invalidazione è locale. Con un solo nodo (oggi) va bene;
  con più nodi servirà un evento condiviso (Redis pub/sub) o una scadenza.
- Un sentiero inserito a mano nel database (seed, SQL) entra nella rete solo al riavvio.
