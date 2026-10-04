# ADR-0020: Percorso semplificato nei sentieri della mappa e bbox per intersezione

- **Stato:** Accettato
- **Data:** 2026-10-04

## Contesto

L'app chiede i sentieri dell'area visibile con `GET /trails?bbox=` e li disegna sulla mappa. `TrailSummaryResponse` aveva solo nome, difficoltà, stato e lunghezza: per disegnare le linee l'app avrebbe dovuto chiamare `GET /trails/{id}` per ognuno, fino a 200 richieste per un movimento della mappa, contro un limite di 60 al minuto per utente.

La query della bbox usava `ST_Contains`: un sentiero compariva solo se stava tutto nell'area visibile. Avvicinando lo zoom, un sentiero che usciva dallo schermo spariva.

## Decisione

- `TrailSummaryResponse` ha un campo obbligatorio `path` (GeoJSON `LineString`, `[lon, lat]`) semplificato con Douglas-Peucker a 0,00005° (circa 5 m): basta per la mappa, il percorso completo resta nel dettaglio. Vale anche per `/trails/nearby`.
- La bbox usa `ST_Intersects`: un sentiero c'è se attraversa l'area visibile. Il limite resta di 200 risultati e l'indice GiST su `path` resta quello usato.

## Conseguenze

- Una richiesta per movimento della mappa basta a disegnare tutti i sentieri visibili.
- Le risposte della lista crescono (qualche centinaio di punti per sentiero dopo la semplificazione); sopra 1 KB sono già in gzip e hanno l'ETag debole.
- Cambio compatibile per i client che ignorano i campi nuovi; il client KMP si rigenera dal contratto.
- `TrailSpatialQueryIT` copre l'intersezione, la bbox che non tocca il sentiero e il percorso semplificato in ordine `[lon, lat]`.
