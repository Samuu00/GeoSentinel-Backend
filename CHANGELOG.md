# Changelog

Formato [Keep a Changelog](https://keepachangelog.com/it-IT/1.1.0/), versioni [SemVer](https://semver.org/lang/it/).
Le versioni non sono ancora taggate in git: i numeri raggruppano le milestone del [piano](docs/MASTER_PLAN.md).

## [Non rilasciato]

### Aggiunto
- Verifica in due passaggi con TOTP (M8, ADR-0025, V15), facoltativa per ogni ruolo:
  - rotte `POST /users/me/mfa/setup` (`startMfaSetup`), `/enable` (`enableMfa`, restituisce 10 codici di recupero una
    volta sola), `/disable` (`disableMfa`) e `/recovery-codes` (`regenerateRecoveryCodes`);
  - `mfaCode` facoltativo in `POST /auth/login` e `POST /auth/google`. Con la 2FA attiva e il codice assente la
    risposta è 401 `mfa-required`; con un codice sbagliato o già usato, 401 `invalid-mfa-code` (422 sulle rotte `/mfa`);
  - `UserProfileResponse.mfaEnabled`, slug nuovo `mfa-already-enabled` (409);
  - reset da `ADMIN` (`POST /admin/mfa-resets`, `resetUserMfa`) dopo una verifica d'identità fuori dall'app: chiude
    tutte le sessioni dell'utente e non vale sulla propria 2FA;
  - protezioni:
    - segreto cifrato con AES-256-GCM (`MFA_ENCRYPTION_KEY` obbligatoria, controllata all'avvio);
    - anti-replay sotto lock sull'utente;
    - codici di recupero salvati come SHA-256 e validi una volta;
    - 5 codici sbagliati all'ora per account → 429;
    - email al terzo codice sbagliato del login, all'attivazione, alla disattivazione e al reset da ADMIN.

  Il client va rigenerato. Test: `TotpTest` (vettori dell'RFC 6238), `MfaSecretCipherTest`, `MfaIT` (16 test).
- Email del reset da un SMTP vero (es. Gmail): `MAIL_SMTP_AUTH` e `MAIL_STARTTLS`, spenti di default; con STARTTLS
  acceso è anche obbligatorio (niente credenziali in chiaro se il TLS non parte). Istruzioni in `.env.example`.
- 422 `birth-date-required` su `POST /auth/google` quando manca `birthDate` per un account nuovo (prima era
  `business-rule`, indistinguibile da email non verificata e identità già collegata): l'app chiede la data e ripete.
- Login con Google (ADR-0024, V14): `POST /auth/google` (`loginWithGoogle`) con l'ID token nativo dell'app; 201 per
  un account nuovo (serve `birthDate`), 200 per uno esistente. Solo email verificate da Google; un `HIKER` con la
  stessa email viene collegato (password azzerata, sessioni chiuse), il personale no (409 `account-managed`); 503
  `identity-provider-unavailable` se le chiavi di Google non si scaricano. `UserProfileResponse.hasPassword`. Il client
  va rigenerato. `GoogleIdTokenVerifierTest`, `GoogleLoginIT`.
- Reset della password con codice via email (V13): `POST /auth/password-reset/request` (`requestPasswordReset`,
  sempre 204, anche per un'email sconosciuta; 3 richieste all'ora per email → 429) e `/confirm`
  (`confirmPasswordReset`, 204; 422 `invalid-reset-code` per codice sbagliato, scaduto, già usato o dopo 5 errori).
  Codice di 16 caratteri (Crockford, `XXXXXXXX-XXXXXXXX`), valido 15 minuti, salvato solo come hash BCrypt; la
  conferma chiude tutte le sessioni e non ne apre una nuova. Email spenta di default (`MAIL_ENABLED`), Mailpit nel
  compose per lo sviluppo. Il client va rigenerato. `PasswordResetIT`.
- Rifugi descritti (V11): `RefugeResponse` con `description`, `altitudeM`, `season`, `phone` (facoltativi) e `amenities`
  (sempre presente, elenco fisso `RefugeAmenity` nell'ordine dell'enum, tabella `refuge_amenities`); gli stessi campi,
  facoltativi, in `CreateRefugeRequest`. Il client va rigenerato. Seed: i due rifugi descritti (nuovo Rifugio Cupone).
  `RefugeDetailsIT`.
- Foto dei rifugi: `MediaTargetType.REFUGE`; le carica solo il gestore del rifugio, le vede ogni utente loggato.
  `MediaUploadIT`.
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
