# Changelog

Formato [Keep a Changelog](https://keepachangelog.com/it-IT/1.1.0/), versioni [SemVer](https://semver.org/lang/it/).
Le versioni non sono ancora taggate in git: i numeri raggruppano le milestone del [piano](docs/MASTER_PLAN.md).

## [Non rilasciato]

### Aggiunto
- `GET /hazards/pending` (`findPendingHazards`, solo `PARK_MANAGER`): le segnalazioni `SUBMITTED` di tutti, ovunque
  siano, paginate, dalla più recente (sort solo su `createdAt`). `/hazards/trail/{id}` vede solo quelle entro 100 m da
  un sentiero: una segnalazione dalla mappa lontana dai sentieri non arrivava mai a un gestore. Serve alla scheda
  Gestione dell'app (M8.1). `PendingHazardsIT`.
- `GET /hazards/mine` (`findMyHazards`): le segnalazioni dell'utente corrente in ogni stato, paginate, dalla più
  recente (sort solo su `createdAt`). Serve alla lista "Le mie segnalazioni" dell'app (M4.3).
- `HazardResponse.createdAt` (obbligatorio): data della segnalazione. Il client va rigenerato.
- `GET /hazards/nearby` (`findHazardsNearby`): i pericoli attivi (`VERIFIED`) di tutti gli utenti intorno a un punto,
  dal più vicino (`radius` in metri, default 5000, massimo 50000; `limit` default 30, massimo 50), solo da loggati.
  Usa l'indice GiST su `location::geography` già in V4. Serve a "Pericoli vicino a me" dell'app (M4.4).

### Modificato
- `GET /hazards/trail/{trailId}`: le segnalazioni `SUBMITTED` le vedono solo chi le ha fatte e i `PARK_MANAGER`
  (prima tutti); al massimo 50 risultati, dalla più recente.
- Prenotazioni: check-in al massimo a 365 giorni e al massimo 5 prenotazioni attive per utente, altrimenti 422 (ADR-0021).
- Upload dei media: il POST presigned scrive su `staging/<key>`; il confirm copia l'oggetto sulla chiave finale e lo
  verifica lì. Il contratto per l'app non cambia (si usano i `formFields` restituiti). `minio-init` aggiunge una regola
  di lifecycle che cancella lo staging dopo un giorno: va rilanciato (`docker compose up -d`).

### Sicurezza
- Audit del 05/10, tutte e 7 le piste confermate da un IT e corrette:
  - una refresh concorrente al cambio password o alla cancellazione dell'account sopravviveva alla revoca: ora lock
    sulla riga dell'utente in `changePassword`, `deleteAccount` e `refresh`;
  - la policy POST di MinIO, valida 10 minuti, sostituiva un file già verificato: ora upload su staging;
  - un ciclo SOS raise → cancel mandava una push agli operatori a ogni giro: al massimo 3 ogni 15 minuti (ADR-0021);
  - un account occupava tutti i posti di un rifugio, e un check-in lontanissimo bloccava il rifugio con un
    `generate_series` enorme: tetto per utente, orizzonte di 365 giorni, `statement_timeout = 30s`;
  - un access token revocato tornava valido per 60 s dopo `exp` (tolleranza di `JwtTimestampValidator`): tolleranza a 0;
  - `GET /hazards/trail/{id}` senza `LIMIT` e con le `SUBMITTED` di tutti: vedi sopra.
- Periodo di grazia di 60 secondi sul refresh token appena ruotato, se il successore non è mai stato usato
  (ADR-0019): una risposta di refresh persa per la rete non chiude più la sessione. Migration V10.

## [0.2.0] - 2026-10-01

Backend per l'app mobile Kotlin Multiplatform (milestone M7).

### Aggiunto
- Contratto OpenAPI stabile in `docs/openapi.json`: `operationId` espliciti, campi `required`, snapshot test.
- Profilo utente (`/users/me`), cambio password con nuovi token, cancellazione dell'account con anonimizzazione.
- Età minima di 14 anni in registrazione, senza salvare la data di nascita.
- Notifiche push FCM: registrazione dei dispositivi, SOS agli operatori, aggiornamenti all'escursionista.
- Compressione gzip, ETag e `304` sul catalogo, `GET /bookings/my` paginato.
- Contratti di retry, refresh e upload nella descrizione OpenAPI; `MediaUploadIT` con MinIO reale.
- Profilo `prod`, `docker-compose.prod.yml` con Caddy (TLS) e backup giornalieri.
- Documentazione: architettura arc42, ADR, guida all'API, runbook, contribuzione, sicurezza.

### Modificato
- Rate limit configurabile e adatto al CGNAT: 30/min per IP su login, 120 sul refresh, 60 per utente.
- `GET /bookings/my` restituisce una pagina invece di una lista (**incompatibile**: rigenerare il client).
- Ridurre la capacità di un rifugio sotto gli ospiti già confermati risponde 409.
- I job programmati non girano durante i test.

### Sicurezza
- Cancellazione dell'account e cambio password revocano gli access token di tutti i dispositivi.
- Limite per account sui tentativi della password attuale.

## [0.1.0] - 2026-09-28

Prima versione completa del backend (milestone da M0 a M6).

### Aggiunto
- Identità: registrazione, login a tempo costante, JWT con blacklist, refresh token ruotati con reuse
  detection, rate limit su Redis, limite di login per account.
- Sentieri, POI e segnalazioni di pericolo con query PostGIS.
- Rifugi e prenotazioni con lock pessimistico e `Idempotency-Key`.
- Ticket di manutenzione con lock ottimistico (`If-Match`) e cache.
- SOS con squadre di soccorso, notifiche asincrone con recupero, conservazione GDPR a 90 giorni.
- Foto via MinIO con presigned POST policy, verifica del contenuto, quarantena e quota.
- Errori RFC 7807 con slug stabili e `traceId`.
- Migrazioni Flyway da V1 a V7 e seed di sviluppo.
- Docker Compose, Dockerfile multi-stage, CI GitHub Actions con gitleaks, Dependabot.
- Suite di integrazione con Testcontainers, test di concorrenza ripetuti.

[Non rilasciato]: https://github.com/Samuu00/GeoSpatial-Backend/compare/98e781c...HEAD
