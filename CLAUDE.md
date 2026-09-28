# GeoSentinel — Backend

Piattaforma per la sicurezza dei sentieri montani, il monitoraggio ambientale e le emergenze outdoor.
Fase attuale: **solo backend**. Il piano completo, con milestone, task, firme e checkpoint, è in [`docs/MASTER_PLAN.md`](docs/MASTER_PLAN.md).

## Come lavoriamo (regola tassativa)
- **Il codice lo scrive l'utente, riga per riga.** Claude fa da mentore, architetto e reviewer: definisce struttura e contratti, poi revisiona.
- Claude scrive o corregge codice **solo quando l'utente lo chiede esplicitamente** ("correggi tu", "sistema"). Altrimenti segnala il problema e indica cosa cambiare.
- Review = bug reali e problemi concreti, con file:riga. Nessuna riscrittura non richiesta.
- Si lavora in italiano. I commenti nel codice sono in italiano; i messaggi di errore delle API (`title`, `detail`) restano in inglese.
- `.env` contiene segreti: non leggerne né stamparne i valori, solo i nomi delle chiavi.

## Stack (decisioni prese)
| Area | Scelta |
|---|---|
| Runtime | Java 21, **Spring Boot 4.1.1** (non 3.x: il supporto OSS di 3.5 è finito). Jackson 3 (`tools.jackson.*`), Spring Security 7, Hibernate 7, Testcontainers 2 |
| Architettura | Hexagonal **pragmatico**, package-by-feature. Le entità JPA sono il modello di dominio. Porte solo per i sistemi esterni (`ObjectStorage`, `TokenBlacklist`) |
| DB | PostgreSQL 16 + PostGIS, Hibernate Spatial + JTS, SRID 4326. Flyway (`ddl-auto=validate`) |
| Sicurezza | `oauth2-resource-server` (Nimbus) con HS256, refresh token opaco (hash SHA-256) con rotazione per famiglia, blacklist dei `jti` su Redis, rate limit token bucket (Lua su Redis) |
| Cache | Redis tramite Boot `RedisCacheManager`, `transactionAware`, **serializer JSON tipizzato per cache** (niente default typing) |
| Storage | MinIO 9.0.3 (+ `okhttp-jvm` esplicito) con **presigned POST policy** per l'upload e GET firmato per il download |
| DTO / mapping | Record Java + MapStruct 1.6.3 (`componentModel=spring`, `unmappedTargetPolicy=ERROR`). Niente Lombok |
| Errori | RFC 7807 `ProblemDetail`, `type` = `https://geosentinel.dev/problems/<slug>` |
| API docs | springdoc-openapi 3.1.1 |
| Test | JUnit 5, Mockito, Testcontainers (`postgis/postgis:16-3.4`, `redis:7-alpine`). Test `*IT` con failsafe |

## Struttura
```
it.unical.geosentinel
├── shared/            domain (AuditableEntity, exception/*), config/*, web (GlobalExceptionHandler, ProblemTypes, ProblemDetailWriter, geo/*)
├── identity/          utenti, auth, sicurezza
├── trail/             sentieri, POI, segnalazioni di pericolo
├── refuge/            rifugi e prenotazioni (lock pessimistico)
├── maintenance/       ticket (lock ottimistico + cache)
├── emergency/         SOS e squadre (lock pessimistico + evento asincrono)
└── media/             metadati file + MinIO
```
Ogni feature ha `domain/`, `application/`, `infrastructure/`, `web/`.
- Controller e DTO stanno in `web/`, i service in `application/`, i repository Spring Data in `infrastructure/persistence/`.
- `domain` non dipende da `web` o `infrastructure`; `web` non usa i repository.
- Una feature non usa i repository di un'altra: passa dal suo service.

## Convenzioni e trappole (da far rispettare nelle review)
- **Coordinate:** JTS `x = longitudine`, `y = latitudine`; GeoJSON `[lon, lat]`. Le geometrie si creano solo con il bean `GeometryFactory` (SRID 4326).
- **Distanze:** `ST_DWithin` su `geometry` 4326 lavora in gradi. Usare il cast `::geography` e un indice GiST sull'espressione `(col::geography)`.
- **Bean:** singleton stateless, campi `private final`, iniezione da costruttore. `Clock` come bean per il tempo (niente `Instant.now()` senza clock).
- **JPA:**
  - `open-in-view=false`, `@ManyToOne(fetch = LAZY)`, `@Enumerated(STRING)` + `CHECK` nel DDL.
  - `@Transactional` sui service, mai sui controller.
  - Si cachano solo DTO, mai entità. Ogni cache si registra nella sua feature (`<feature>/infrastructure/cache`) con `CacheConfig.typed(...)`, altrimenti `@Cacheable` fallisce.
  - Le view restituite dai service non contengono geometrie JTS: `GeoJsonLineString`/`GeoPointDto` via `GeometryMapper`.
  - Colonne generate (`length_m`) e `@Version` si aggiornano solo al flush: fare flush prima di costruire il DTO di risposta.
  - Query native: un parametro facoltativo si confronta con `CAST(:p AS varchar) IS NULL`, altrimenti PostgreSQL non ne deduce il tipo.
- **Lock ottimistico:** oltre a `@Version`, confronto esplicito della `expectedVersion` inviata dal client (`If-Match`).
- **Lock pessimistico:**
  - `lock_timeout = 3s` (Hikari `connection-init-sql`);
  - ordine fisso dei lock (alert → team);
  - capacità dei rifugi verificata **notte per notte**.
- **Asincrono:**
  - executor dedicato `sosNotificationExecutor` con `CallerRunsPolicy`;
  - il `TaskDecorator` ripristina l'MDC precedente;
  - niente `initialize()` manuale;
  - `@TransactionalEventListener(AFTER_COMMIT)` con eventi che contengono dati, non entità.
- **Jackson:** usare `tools.jackson.databind.json.JsonMapper` (Jackson 3). Jackson 2 (`com.fasterxml...ObjectMapper`) arriva solo come dipendenza transitiva di springdoc: **non esiste come bean**.
- **Eccezioni:** la rinomina `OptimisticConflictException` serve a evitare lo scontro con `java.util.ConcurrentModificationException`. Occhio agli auto-import.

## Stato di avanzamento
**Fatto e revisionato:**
- `application.properties`:
  - credenziali da `.env`, importato con `spring.config.import=optional:file:.env[.properties]`; chiavi `POSTGRES_DB` (nome del DB, non l'URL), `POSTGRES_USER`, `POSTGRES_PASSWORD` (le stesse lette dall'immagine Postgres), `JWT_SECRET`; l'URL JDBC si costruisce in `application.properties`;
  - rimosso il dialect deprecato;
  - aggiunti `open-in-view=false`, time zone UTC, `lock_timeout`, problemdetails.
- `pom.xml` (M0.1):
  - aggiunti: resource-server (nome Boot 4), validation, actuator, `flyway-database-postgresql`, springdoc, MapStruct + processor, Testcontainers, failsafe;
  - MinIO rimandato a M3.
- `shared/domain`:
  - `AuditableEntity`: UUID, auditing, `equals`/`hashCode` compatibili con i proxy. `created_by` è nullable (la registrazione e il sistema non hanno un utente).
  - Eccezioni: `DomainException`, `ResourceNotFoundException`, `BusinessRuleViolationException`, `OptimisticConflictException`.
- `shared/config`:
  - `JpaAuditingConfig` (auditor dal `sub` del JWT) e `GeometryConfig` (`GeometryFactory` + `Clock` UTC);
  - `AsyncConfig`;
  - `CacheConfig`: bean dei default, customizer `transactionAware`, helper `CacheConfig.typed(...)` che ogni feature usa per registrare la propria cache;
  - `OpenApiConfig`.
- `shared/web`:
  - `GlobalExceptionHandler`: un solo punto di arricchimento in `handleExceptionInternal`, `errors[]` uniforme per le tre eccezioni di validazione, 403 per `@PreAuthorize`;
  - `ProblemTypes`;
  - `ProblemDetailWriter`: Jackson 3 + `ProblemDetailJacksonMixin`;
  - `geo/`: `GeoPointDto`, `GeoJsonLineString` (validato nel costruttore, `List<List<Double>>`), `GeometryMapper`.
- Test: `GeoJsonLineStringTest`, `ProblemDetailWriterTest`, `RateLimitFilterTest` (verdi).

**M2 in corso — scritto dall'utente e revisionato (22/09):**
- `identity/domain`: `User` (estende `AuditableEntity`, email normalizzata, factory `createHiker`/`create`), `RefreshToken` (con `familyExpiresAt`, `revoke(Instant)`, `isExpired` che copre anche la scadenza assoluta), `Role`.
- `identity/application`: `AuthService` (register, login a tempo costante con hash fittizio, refresh con rotazione e reuse detection, logout), `AuthTokens`, `IssuedAccessToken`, `RegisterCommand`, porta `TokenBlacklist`.
- `identity/infrastructure/persistence`: `UserRepository` (`findByEmail`/`existsByEmail`, email già normalizzata), `RefreshTokenRepository` (`revokeIfActive`/`revokeFamily` atomici).
- `identity/infrastructure/redis`: `RedisTokenBlacklist` (fail-closed), `RedisRateLimiter`, `RateLimitPolicy` (con `withFailClosed()`), `RateLimitDecision`.
- `identity/infrastructure/security`: `JwtTokenService`, `JwtProperties` (fail-fast sui byte del segreto), `SecurityConfig`, `JtiBlacklistValidator`, `RateLimitFilter`, `CurrentUser`, `ProblemAuthenticationEntryPoint`, `ProblemAccessDeniedHandler`.
- `scripts/token_bucket.lua`: verificato su Redis 7 (5 richieste al minuto: la sesta riceve 429 con `Retry-After` di circa 12 s; ricarica corretta dopo 300 ms).
- `GlobalExceptionHandler`: aggiunto il 401 per `AuthenticationException`.
- `application.properties`: URL JDBC costruito, `geosentinel.jwt.*`, actuator limitato a `health`, shutdown graceful, springdoc spento fuori da `local`.
- Package rinominato da `org.example.geosentinel` a **`it.unical.geosentinel`**.

**M2.7 fatto e revisionato (22/09, notte):**
- `identity/web`: `AuthController` + DTO (`RegisterRequest`, `LoginRequest`, `RefreshRequest`, `TokenResponse`).
- `@MaxUtf8Bytes` in `shared/web/validation` (annotazione + validatore in un file) sulla password di register e login: prima `@Size(max = 128)` contava i caratteri e una password lunga dava 500 da BCrypt.
- `RegisterRequest.email` con `@Size(max = 254)`.
- `logout`: body facoltativo (`required = false`); `AuthService.logout` revoca la famiglia solo se il refresh token appartiene al `sub` del JWT.
- `User`: `email` `length = 254`, `display_name` `length = 100` (da replicare in V2).
- Test: `MaxUtf8BytesTest`, `AuthServiceLogoutTest`.

**M1.3 in corso — `trail/domain` scritto dall'utente e revisionato (22/09, notte):**
- `Trail` (con `@Version`, invarianti SRID 4326 e ≥ 2 punti, regola `CLOSED → WARNING` vietata), `Poi`, enum `Difficulty`, `TrailStatus`, `PoiType`.
- Correzioni: `Trail.lengthM` con `@Generated(event = {INSERT, UPDATE})` (la colonna generata non veniva riletta: `null` dopo la creazione, vecchia dopo un cambio di path); `Poi.create(name, type, location)` senza il parametro `trail` che veniva ignorato.
- Completato il 23/09 e il 24/09 (vedi sotto).

**M1.3 + M4.1 (senza web) — `trail` scritto dall'utente e revisionato (23/09):**
- `trail/domain`: `HazardReport` (transizioni `SUBMITTED → VERIFIED/REJECTED`, `VERIFIED → RESOLVED`), `HazardType`, `HazardStatus`, `Severity`.
- `trail/infrastructure/persistence`: `TrailRepository` (`findWithinDistance`, `findContainedIn` con `LIMIT`), `PoiRepository.findNearby`, `HazardReportRepository.findActiveNearTrail`.
- `trail/application`: `TrailService`, `PoiService`, `HazardReportService`; command e view in `application/dto` (`TrailCommands`, `PoiCommands`, `HazardCommands`).
- `trail/infrastructure/cache/TrailCacheConfig`: registra la cache `trails` (`TrailDetail`, 10 min) con `CacheConfig.typed(...)`. Test: `TrailDetailCacheSerializationTest`.
- Correzioni:
  - `HazardReport.description` non veniva mai assegnata;
  - la cache `trails` non era registrata: con `disableCreateOnMissingCache()` il primo `getTrail` falliva;
  - le view esponevano geometrie JTS (non serializzabili): ora `GeoJsonLineString`/`GeoPointDto` tramite `from(entity, GeometryMapper)`;
  - `create`/`update`/`changeStatus` di `TrailService` fanno flush prima del DTO (`lengthM` e `version` altrimenti vecchi); `create` restituisce `TrailDetail`, non l'entità;
  - `PoiRepository`: `CAST(:type AS varchar)` (un `null` non tipizzato fa fallire PostgreSQL);
  - `HazardReport` salva `UUID reporterId` invece di `@ManyToOne User`: `HazardReportService` usava `UserRepository` di `identity`;
  - `changeStatus(SUBMITTED)` → `BusinessRuleViolationException` (prima `IllegalArgumentException` → 500);
  - raggio massimo 50 km anche sui POI, `LIMIT` 200 sulla bbox, range lat/lon in `BoundingBox`, `@PreAuthorize("hasRole('HIKER')")` su `create` delle segnalazioni, 404 su `findNearTrail` per un sentiero inesistente.
- Da ricordare nel web: `BoundingBox` lancia `IllegalArgumentException`, il controller deve tradurla in 400.
- In V3: `hazard_reports.reporter_id uuid NOT NULL REFERENCES users(id)` con indice.

**M5.2 per `trail` — web scritto dall'utente e revisionato (24/09):**
- `trail/web`: `TrailController`, `PoiController`, `HazardReportController`; `dto/` con `TrailDtos`, `PoiDtos`, `HazardDtos` (request + response) e `TrailMapper` (MapStruct, `uses = GeometryMapper`: request → command, view → response).
- Rotte: `/api/v1/trails` (`?bbox=`, `/nearby`, `/{id}`, `/{id}/status`), `/api/v1/pois` (`/{id}`, `/nearby`, `/trail/{trailId}`), `/api/v1/hazards` (`/{id}`, `/trail/{trailId}`, `/{id}/status`). Le segnalazioni stanno sotto `/hazards` e non sotto `/trails/**`, dove le GET sono `permitAll`.
- Correzioni:
  - non compilava: `TrailMapper.toResponse(TrailDetailResponse)` invece di `TrailDetail`, `toCommand` delle segnalazioni che restituiva la request invece di `CreateHazardReportCommand`;
  - bbox letta con `@RequestBody` su una GET → `@RequestParam`; il parsing (`parseBoundingBox`) traduce `IllegalArgumentException` in `ResponseStatusException(400)`;
  - `@NotBlank` su un enum (`UpdateTrailStatusRequest`) → 500 dalla validazione: ora `@NotNull`;
  - `CreatePoiRequest.location` senza `@Valid` (lat=200 accettata);
  - `limit` dei POI `@Max(100)` ridotto in silenzio a 50 dal service → `@Max(50)`;
  - import inutili, tra cui `AuthService` di `identity` e `security.core.parameters.P`;
  - tolto `@Validated` dai controller: Spring MVC valida già i parametri (`HandlerMethodValidationException`).
- Decisioni di sicurezza:
  - `HazardResponse` non espone `reporterId` (dato personale);
  - `HazardReportService.getHazard` con `@PostAuthorize`: una `REJECTED` la leggono solo `PARK_MANAGER` e il segnalatore (`authentication.name` = `sub`);
  - `PoiService.findByTrail` → 404 per un sentiero inesistente;
  - `Trail.name` `length = 150`, `Poi.name` `length = 100` (da replicare in V3).
- Master plan aggiornato: struttura di `trail/web`, V3, M4.1, tabella endpoint e regola dei mapper in M5.2.

**M1.3 + M4.2 (senza web) — `refuge` scritto dall'utente e revisionato (24/09):**
- `refuge/domain`: `Refuge` (capacity > 0, SRID 4326), `Booking` (`create`, `cancel(Instant)`, `isSameRequest`), `BookingStatus`.
- `refuge/infrastructure/persistence`: `RefugeRepository` (`findByIdForUpdate` con `PESSIMISTIC_WRITE`, `findNearby`), `BookingRepository` (`maxNightlyOccupancy` notte per notte con `generate_series`, `findByIdAndUserId`, `findByUserIdAndIdempotencyKey`, `findActiveOverlappingBookings`).
- `refuge/application`: `RefugeBookingService` (`book`, `cancel`, `availability`, `getRefuge`, `findNearby`, `getUserBookings`, `createRefuge`, `updateCapacity`), `dto/RefugeBookingCommands`.
- Correzioni:
  - **overbooking:** `book` usava `findById` senza lock; ora `findByIdForUpdate`;
  - `CapacityExceededException` senza handler → 500: spostata in `shared/domain/exception`, handler 409 `capacity-exceeded`;
  - `RefugeView` esponeva un `Point` JTS: ora `GeoPointDto` via `from(refuge, GeometryMapper)`;
  - `Booking.cancel` ignorava `now` e usava `LocalDate.now()`: ora `LocalDate.ofInstant(now, UTC)`;
  - `Booking` accettava `checkIn == checkOut` (il CHECK del DDL no);
  - `cancel` di una prenotazione altrui → 422 che ne rivelava l'esistenza: ora `findByIdAndUserId` → 404;
  - `findNearby` dei rifugi senza tetto al raggio: ora 50 km;
  - `@PreAuthorize("hasRole('HIKER')")` su `book`; import e `save()` inutili rimossi.
- **Idempotenza D12 implementata:** `Booking.idempotencyKey` (`NOT NULL`, non aggiornabile), `book(userId, idempotencyKey, cmd)`. Ordine: lock sul rifugio → ricerca della chiave (stessa richiesta → prenotazione esistente; body diverso → `IdempotencyKeyReusedException`, 422 `idempotency-key-reused`) → validazione delle date → capacità → save. La chiave si cerca **dopo** il lock, così due retry simultanei si accodano e il secondo trova la riga del primo invece del vincolo `UNIQUE`.
- Test: `BookingTest`, `RefugeBookingServiceTest` (verdi).
- In V4: `bookings.idempotency_key uuid NOT NULL` + `UNIQUE (user_id, idempotency_key)`.
- Limite noto: `updateCapacity` può scendere sotto le prenotazioni confermate.
- Master plan aggiornato: struttura (§1), M1.3, M4.2 (ordine lock → chiave), M5.1.

**M5.2 per `refuge` — web scritto dall'utente e revisionato (25/09):**
- `refuge/web`: `RefugeController` (`/api/v1/refuges`: `/{id}`, `/nearby`, `/{id}/availability`, `POST`, `PATCH /{id}/capacity`), `BookingController` (`/api/v1/bookings`: `POST`, `/my`, `GET /{id}`, `DELETE /{id}`); `dto/` con `RefugeDtos`, `BookingDtos`, `RefugeMapper` (MapStruct, `uses = GeometryMapper`).
- Correzioni:
  - non compilava: `book` chiamato senza la chiave; `CreateBookingRequest` senza `checkOut` (con `unmappedTargetPolicy=ERROR` il mapper non si generava);
  - `Idempotency-Key` facoltativo e `String` → `UUID` obbligatorio: 428 `precondition-required` se manca (override di `handleServletRequestBindingException`, vale anche per `If-Match`), 400 se non è un UUID;
  - `@FutureOrPresent` su `checkIn` tolto: usava il fuso della JVM e un retry dopo il check-in riceveva 400 invece della prenotazione (D12);
  - la `Location` del POST puntava a `/bookings/{id}` inesistente: aggiunti `GET /{id}` e `RefugeBookingService.getBooking` (404 se non tua);
  - `CreateRefugeRequest.location` senza `@Valid`, `capacity` senza `@Min(1)`;
  - `@Validated` tolto da `RefugeController`; parametro `radiusMeters` → `radius` come trail/POI; import inutili (tra cui `jakarta.persistence.criteria.CriteriaBuilder` in un DTO).
- Decisioni di sicurezza:
  - `managerId` non arriva più dal body: il gestore è chi crea il rifugio (`sub` del JWT), `RefugeMapper.toCommand(request, managerId)`;
  - `updateCapacity(actorId, actorIsAdmin, …)`: solo il gestore del rifugio o un ADMIN (`CurrentUser.isAdmin()`), altrimenti 403;
  - un retry con la stessa chiave risponde 200 (`BookingResult.created = false`), la prima creazione 201 + `Location`.
- `Refuge.name` `length = 120` (da replicare in V4: `name varchar(120)`).
- Test: `BookingControllerTest` (MockMvc standalone + `GlobalExceptionHandler`: 428/400 sull'header), `RefugeBookingServiceTest` esteso (retry → `created = false`, capacità solo gestore/admin). Verdi.
- Master plan aggiornato: tabella endpoint (rotte reali di refuge), M4.2, M2.2 (`CurrentUser.isAdmin`), M5.1 (428), V4.

**Limite per account sul login (26/09, scritto da Claude su richiesta):** porta `LoginAttemptLimiter` + `RedisLoginAttemptLimiter` (`rl:login:{sha256(email)}`, 10 / 15 min, fail-closed), controllo come prima istruzione di `AuthService.login`, `RateLimitExceededException` → 429 + `Retry-After`. Test: `AuthServiceLoginLimitTest`.

**Pulizia refresh token (26/09, scritta da Claude su richiesta):** `RefreshTokenRepository.deleteExpired` cancella per `family_expires_at` (non `expires_at`: si perderebbe la reuse detection dei token vecchi di una sessione ancora viva); `RefreshTokenCleanupJob` ogni notte alle 03:30 UTC; `@EnableScheduling` in `shared/config/SchedulingConfig`. In V2: indice su `refresh_tokens(family_expires_at)`.

**M1.3 + M4.4 + M5.2 per `maintenance` — scritto dall'utente, revisionato e corretto da Claude su richiesta (26/09):**
- `maintenance/domain`: `MaintenanceTicket` (target sentiero, rifugio o segnalazione; `assign`/`startWork`/`resolve`/`cancel`), `TicketStatus`, `TicketPriority`. `application`: `MaintenanceService`, `dto/MaintenanceTicketCommands`. `infrastructure`: `MaintenanceTicketRepository`, `cache/MaintenanceCacheConfig`. `web`: `MaintenanceController` (`/api/v1/maintenance-tickets`), `dto/MaintenanceDtos`, `MaintenanceMapper`.
- Correzioni:
  - non partiva: l'entità non estendeva `AuditableEntity` e non aveva `@Id` (`ticket_id`, `createdAt` mai valorizzato);
  - usava `TrailRepository`, `RefugeRepository`, `HazardReportRepository`, `UserRepository`: ora `TrailService.getTrailOrThrow`, i nuovi `RefugeBookingService.getRefugeOrThrow`, `HazardReportService.getHazardOrThrow` e `identity/application/UserQueryService.isActiveUser`;
  - lock ottimistico assente: ora `expectedVersion` da `If-Match` su PUT, assign e status (428/400/409), `ETag` nelle risposte, flush prima del DTO; `version` fuori dal body;
  - cache `maintenance-by-trail` mai registrata né usata: `byTrail` con `@Cacheable`, evict su `#result.trailId()`;
  - `assign` su un ticket `IN_PROGRESS` lo riportava ad `ASSIGNED`;
  - più target nella stessa richiesta accettati in silenzio: ora esattamente uno (422);
  - ruoli allineati al piano (solo `PARK_MANAGER`, prima anche `RESCUE_OPERATOR`); `Page` grezzo → `PagedModel`; N+1 sulla ricerca (`@EntityGraph`); `title` `length = 150`; import `org.geolatte.geom.M` e metodi del repository inutilizzati rimossi.
  - `?sort=` libero finiva nell'`ORDER BY` della `@Query` (campo sconosciuto → errore Hibernate → 500): whitelist `SORTABLE_FIELDS` nel controller (`createdAt`, `updatedAt`, `status`, `title`) → 400. `priority` escluso: l'enum è una stringa e si ordinerebbe in ordine alfabetico. Non un handler globale: `InvalidDataAccessApiUsageException` coprirebbe anche bug veri. Ogni nuovo endpoint paginato (es. SOS) fa lo stesso.
- Confermate dall'utente: target multipli (sentiero/rifugio/segnalazione) e rotte sotto `/api/v1/maintenance-tickets`.
- Test: `MaintenanceTicketTest` (transizioni + round-trip della cache), `MaintenanceControllerTest` (sort, `If-Match`). In V5: vedi la riga aggiornata nel master plan.

**M1.3 + M4.3 + M5.2 per `emergency` — scritto dall'utente, revisionato e corretto da Claude su richiesta (27/09):**
- `emergency/domain`: `SosAlert` (`ACTIVE → DISPATCHED → RESOLVED`, `CANCELLED`; `operatorId`, `closedAt`, `locationUpdatedAt`; niente `@Version`), `RescueTeam` (`AVAILABLE/ON_MISSION/OFF_DUTY`), `SosAlertRaisedEvent(alertId, lon, lat, raisedAt)`. `application`: `SosService`, `SosDataRetentionJob`, `dto/SosCommands`. `infrastructure`: repository con lock, `notification/SosNotificationListener` + `SosNotificationRecovery`. `web`: `SosController` (`/api/v1/sos`), `RescueTeamController` (`/api/v1/rescue-teams`), `SosDtos`, `RescueTeamDtos`, `SosMapper`.
- Correzioni:
  - `dispatchTeam`/`resolveAlert`/`createTeam` erano `@Transactional(readOnly = true)`: modifiche perse, INSERT rifiutato;
  - `updateLiveLocation` ignorava `hikerId` (chiunque spostava l'SOS altrui); `cancelSos` altrui → 422 che rivelava l'esistenza: ora 404 con `findByIdAndHikerIdForUpdate`;
  - nessun lock pessimistico: ora alert → team su dispatch/resolve/cancel; secondo dispatch → 409 `alert-already-taken` (`AlertAlreadyTakenException` in `shared`);
  - un secondo SOS riceveva 422: ora mai rifiutato, advisory lock per escursionista + `Idempotency-Key` facoltativa → 200 con l'allerta esistente;
  - `EmergencyAsyncConfig` duplicava l'executor (AbortPolicy, `initialize()` manuale, niente MDC): rimosso, si usa `sosNotificationExecutor`;
  - `createTeam` con `@PathVariable` sul body; `GET /{id}` dell'allerta vietato al proprietario (`@PostAuthorize`); view con `Point` JTS; evento con `Point`, note mediche e `hikerId` nei log; enum confrontato con stringa in JPQL; `Page` grezzo e sort libero (whitelist); `setAvailable` liberava una squadra in missione.
- Scelte (27/09): stati dell'utente mantenuti (niente `IN_CHARGE`: l'esclusività è sul dispatch, `operatorId` registra l'operatore); rotte `/api/v1/sos` e `/api/v1/rescue-teams`; recupero notifiche con `notified_at` (ogni 30 s); conservazione GDPR a 90 giorni da `closed_at` (posizione arrotondata a 0.01°, note cancellate, `anonymized_at`); `POST /api/v1/sos` escluso dal rate limit; `createTeam` solo `ADMIN`. `notified_at`/`anonymized_at` non mappati nell'entità.
- Test: `SosAlertTest`, `RateLimitFilterTest` (SOS senza rate limit). V6 aggiornata nel master plan.

**M3 per `media` — scritto dall'utente, revisionato e corretto da Claude su richiesta (28/09):**
- `media/domain`: `MediaFile` (`PENDING_UPLOAD → READY / QUARANTINED`, `@Version`, `size_bytes` dichiarata poi reale), `MediaStatus`, `MediaTargetType` (`TRAIL`, `HAZARD_REPORT`), `MediaPolicy` (whitelist JPEG/PNG/WebP, 10 MB, quota 1 GB, magic bytes). `application`: `MediaService`, `MediaCleanupJob`, porta `ObjectStorage`, `dto/MediaCommands`. `infrastructure`: `MediaFileRepository`, `storage/MinioObjectStorage` + `MinioConfig` + `MinioProperties`. `web`: `MediaController` (`/api/v1/media`), `MediaDtos`, `MediaMapper`.
- `pom.xml`: `minio` 8.5.17 → **9.0.3** (la 8.5 portava `bcprov` 1.78.1, CVE-2025-8916) + **`okhttp-jvm` 5.3.2 esplicito**. Trappola: da okhttp 5 l'artefatto `okhttp` è multipiattaforma e con Maven arriva un jar vuoto; senza `okhttp-jvm` compila, ma va in `NoClassDefFoundError` alla prima chiamata. In MinIO 9 `Method` è `io.minio.Http.Method`.
- Correzioni:
  - non compilava (`new DomainException(...)` astratta) e non partiva (`MinioProperties` `@Validated` senza chiavi `geosentinel.storage.minio.*`); bucket letto da due property diverse;
  - media di altri leggibili e scaricabili da chiunque: ora il proprietario vede tutto, gli altri solo i `READY` allegati a un'entità visibile (404 altrimenti);
  - il confirm non verificava nulla: ora dimensione ≤ dichiarata e magic bytes; se non tornano, `QUARANTINED` + delete dell'oggetto;
  - `response-content-type` messo sul PUT (inutile): ora sul GET, con `Content-Disposition: attachment`;
  - object key con `ownerId` e filename del client: ora `yyyy/MM/uuid.ext`; `ownerId` e object key tolti dalle risposte;
  - `entityType` stringa libera senza verifica: ora enum, esistenza del target controllata tramite `TrailService`/`HazardReportService`;
  - creazione del bucket all'avvio (timeout MinIO di 5 minuti): ora la fa `minio-init`; un client di firma sul `publicEndpoint`, region fissa, timeout brevi;
  - `Page` grezzo e sort libero → `PagedModel` + whitelist; 201 + `Location`; codice e import inutilizzati rimossi.
- Limiti chiusi (28/09, scelte di Claude su richiesta):
  - upload con **presigned POST policy** (`content-length-range`, `eq $Content-Type`, `eq $key`) invece del PUT: è MinIO a rifiutare un file troppo grande o di un altro tipo. Il client fa un POST multipart con `formFields` + `file`;
  - `MediaCleanupJob` ogni 15 min: upload non confermati oltre TTL + 1 h → cancella la riga (`deleteIfPending` condizionale) e poi l'oggetto;
  - i media di una segnalazione `REJECTED` seguono il `@PostAuthorize` di `HazardReportService.getHazard` (riusato, un'unica fonte della regola);
  - foto su un sentiero solo `PARK_MANAGER` (`@PreAuthorize` con SpEL sul command), su una segnalazione solo il segnalatore;
  - quota con advisory lock per utente (`pg_advisory_xact_lock(int, int)`: keyspace separato dal lock bigint degli SOS);
  - `MediaRejectedException` → 415 / 413 / 422 (`media-*`), `StorageUnavailableException` → 503; tipo e dimensione li valida solo `MediaPolicy`, non il DTO;
  - rotta `POST /api/v1/media/upload-requests` come nel piano; `DELETE` al proprietario o a un ADMIN.
- Configurazione: nel `.env` servono `MINIO_ROOT_USER` e `MINIO_ROOT_PASSWORD` (le sceglie chi installa, le legge anche l'immagine MinIO), altrimenti l'avvio fallisce. `MINIO_BUCKET`, `MINIO_ENDPOINT` e `MINIO_PUBLIC_ENDPOINT` hanno un default (`geosentinel-media`, `http://localhost:9000`); il public endpoint va cambiato per un telefono (IP del PC) o per l'emulatore Android (`10.0.2.2`).
- Test: `MediaPolicyTest`, `MediaServiceTest`. Master plan aggiornato: §1, M0.1–M0.3, M3 riscritta, V7, M5.1, M5.2, catena di review di M3.

**Tutte le feature sono scritte** (M2 compreso), le migrazioni V1–V7 sono applicate e l'app parte: restano i checkpoint.

**M0.4 — `Dockerfile` + `.dockerignore` scritti dall'utente, corretti da Claude su richiesta (28/09):** multi-stage alpine, utente non root, layer estratti con `-Djarmode=tools extract --layers --launcher` (Boot 4 ha rimosso `layertools`: `Unsupported jarmode`), file dell'app di proprietà di root (niente `--chown`), tolti `bash` e `java.security.egd`. Verificato: `docker build` verde, una modifica solo a `src` lascia in cache `go-offline` e il layer delle dipendenze, il processo gira come `geosentinel` (uid 100) e non può scrivere in `/app`, Boot 4.1.1 parte e si ferma su Flyway (nessun DB: atteso). 

**M0.2 — `docker-compose.yml` scritto dall'utente, corretto da Claude su richiesta (28/09):**
- Correzioni: bucket MinIO **pubblico** (`anonymous set download`) → `none`; Redis senza `--requirepass` (l'app manda `REDIS_PASSWORD` → `WRONGPASS`); `exit 0` in `minio-init` che nascondeva gli errori → `set -e`; credenziali con default deboli (`minioadmin`, segreto JWT pubblico) → `${VAR:?}`; backend con chiavi inesistenti (`GEOSENTINEL_SECURITY_JWT_SECRET_KEY`, `..._DEFAULT_BUCKET`, `MINIO_BUCKET_NAME`) e senza `JWT_SECRET` → `env_file: .env` + nomi dei servizi, sotto `profiles: ["app"]`; `stringtype=unspecified` tolto; porte di Postgres/Redis/console solo su `127.0.0.1`.
- **Immagini MinIO ufficiali non più scaricabili** (Docker Hub: repository inesistente, quay.io: 401): `pgsty/minio` e `pgsty/mc` (fork della community) con tag `RELEASE.*` pinnato.
- Verificato: tutti `healthy`, `minio-init` exit 0, PostGIS 3.4.3, bucket privato (GET anonimo → 403), Redis rifiuta chi non ha la password.
- **Porta 5432 occupata da un PostgreSQL di Windows** sul PC dell'utente: nel `.env` serve `DB_PORT=5433` (la leggono sia il compose sia l'app).

**Configurazione:** un solo formato, `.properties` (i file `.yml` sono stati rimossi). I profili si aggiungono come `application-<profilo>.properties`.

**Aperti:**
- `RoleHierarchy`: da verificare che valga anche per `authorizeHttpRequests` e non solo per la method security (prova: token `ADMIN` su una rotta `PARK_MANAGER`).
- `GeoSentinelApplicationTests` è un `@SpringBootTest`: in `./mvnw test` cerca Postgres e fallisce finché mancano DB e migrazioni. Da sostituire con `MigrationIT` (M6.2) o rimuovere.
- `pom.xml`: `groupId` ancora `org.example` (il package è `it.unical.geosentinel`).
- `src/main/resources/static` e `templates`: cartelle vuote del progetto iniziale, da rimuovere.
- `application-local.properties` fatto (28/09): solo differenze dal base (flyway con `db/seed`, springdoc acceso, logging SQL), nessun segreto. Redis nel base: `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD` (vuota di default).
- Nessun test copre ancora `MaintenanceService` (cache evict su `#result`, confronto di `expectedVersion`): li coprirà `MaintenanceOptimisticLockIT` (M6.2), che richiede V5.
- Flusso media mai provato contro un MinIO vero (POST policy, `statObject`, lettura dei primi byte): serve il compose (M0.2) e V7. Da valutare `MediaUploadIT` con il modulo Testcontainers MinIO.

**Correzioni della review completa (22/09, sera):**
- `RateLimitFilter`: un bucket per policy (`rl:auth:*` separato da `rl:*`). Con la chiave condivisa le GET pubbliche ricaricavano il bucket del login, aggirando il limite di 5 al minuto. `Retry-After` arrotondato per eccesso. Test: `RateLimitFilterTest`.
- `SecurityConfig`: `BearerTokenResolver` che ignora l'header `Authorization` su register/login/refresh (un access token scaduto allegato dal client dava 401 anche su rotte `permitAll`); rimosso il campo `jwtProperties` inutilizzato.
- `AuthService`: `refresh-ttl` e il nuovo `session-max-ttl` letti dalla configurazione con `@Value` (prima erano costanti e la property veniva ignorata); `refreshTtl` rimosso da `JwtProperties`.
- `ProblemAuthenticationEntryPoint`: `error="invalid_token"` solo se è arrivato un token (RFC 6750).
- `CacheConfig`: `disableCreateOnMissingCache()`: una cache non registrata con `typed(...)` fallisce invece di usare il serializer JDK.
- `CurrentUser`: senza autenticazione restituisce 401, non 403.
- `spring.task.execution.mode=force`: il bean `sosNotificationExecutor` non sostituisce più l'executor di default di Boot.

**Revisione del piano (22/09):** aggiunte le decisioni D11 (password max 72 byte UTF-8, BCrypt) e D12 (`Idempotency-Key` su SOS e prenotazioni), il task M0.5 (CI + Dependabot), login a tempo costante e limite per account, scadenza assoluta della sessione di refresh, magic bytes e `Content-Disposition: attachment` sui media, recupero delle notifiche SOS perse (`notified_at`), conservazione delle posizioni SOS.
- Nel PATH c'è `javap` di un JDK 15; Maven usa correttamente il JDK 21 (`JAVA_HOME`).

**M1.1 — migrazioni V1–V7 + seed scritti dall'utente, riscritti da Claude su richiesta (28/09):**
- File: `V1__extensions`, `V2__users_and_auth`, `V3__trails_and_refuges` (trails, pois, refuges, bookings), `V4__hazard_reports`, `V5__maintenance`, `V6__emergency`, `V7__media`, `db/seed/R__local_seed.sql`. Nomi dell'utente mantenuti (la tabella del master plan è allineata).
- Le versioni dell'utente non corrispondevano alle entità (l'app non sarebbe partita): tabelle (`refuge_bookings` invece di `bookings`, `pois` e `refresh_tokens` assenti), colonne (`route`/`path`, `is_open`/`status`, `hazard_type`/`type`, `username`, `first_name`…), niente `created_by`/`updated_by`, enum diversi (`HARD`, `URGENT`, `CLOSED`, `PENDING`, `DELETED`), `version` su tabelle senza `@Version`, mancavano `length_m` generata, `idempotency_key`, `location_updated_at`, `operator_id`, `anonymized_at`, `manager_id`, i GiST su `::geography`, `UNIQUE (user_id, idempotency_key)` e l'indice unico dell'allerta attiva. Il vincolo "esattamente un target" dei ticket rompeva `forHazard` (ha anche il sentiero): ora `num_nonnulls(...) >= 1`. Tolte `uuid-ossp`/`pgcrypto` (inutili).
- Seed: l'hash BCrypt era inventato e senza `{bcrypt}` (il `DelegatingPasswordEncoder` avrebbe fallito): ora hash vero di `Password123!`; email minuscole; niente media (oggetto assente nel bucket). Utenti: `admin@`, `manager@`, `rescuer@`, `hiker@`, `hiker2@geosentinel.dev`.
- Verificato sul Postgres del compose: 8 migrazioni applicate, `validate` passa, app avviata; `length_m` 3193 e 4525 m; `EXPLAIN` usa `idx_trails_path_geog`; login di `hiker@` → 200, password errata → 401, GET di un sentiero → 200.
- Sul PC dell'utente la 8080 è occupata da un'altra applicazione (non GeoSentinel): per provare in parallelo `--server.port=...`.
- **Migrazioni ora applicate: sono immutabili.** Ogni modifica va in una `V8__...` nuova (altrimenti Flyway rifiuta l'avvio per checksum diverso).

**M0 completata (28/09, scritta da Claude su richiesta):**
- Tracing: `spring-boot-micrometer-tracing-opentelemetry` + `micrometer-tracing-bridge-otel` (servono entrambi in Boot 4: il bridge nel modulo è opzionale). `ProblemDetailWriter` aggiunge il `traceId` dall'MDC come `GlobalExceptionHandler`. Verificato: 404 e 401 hanno `traceId`.
- `.github/workflows/ci.yml` (job `verify` con `./mvnw -B verify` + job `docker`), `.github/dependabot.yml` (maven, docker, docker-compose, github-actions). Action: `checkout@v7`, `setup-java@v6`.
- Checkpoint di M0.1 e M0.3 verificati e spuntati.
- **Resta all'utente:** secret scanning + push protection (Settings → Code security su GitHub). Checkpoint di M0.5 (PR rossa con un test rotto, prima PR di Dependabot) verificabili solo dopo il push.
- **La CI sarà rossa finché:** `GeoSentinelApplicationTests` cerca Postgres sulla 5432 e `support/AbstractIntegrationTest.java` (in scrittura dall'utente) non compila (`org.springframework.boot.test.autoconfigure.web.servlet` non esiste in Boot 4: il package è cambiato).

**Prossimo passo:**
1. **M6.1 `AbstractIntegrationTest`** (in corso) + **`MigrationIT`**, che sostituisce `GeoSentinelApplicationTests`: la CI torna verde.
2. Checkpoint manuali di M2 (curl: register → login → logout → 401, refresh riusato, 429), poi M3 (upload su MinIO vero) e M4.

## Comandi
```bash
./mvnw -q -DskipTests compile                          # compilazione
./mvnw test                                            # unit test (surefire)
./mvnw verify                                          # + integration test *IT (failsafe, richiede Docker)
./mvnw test -Dtest='GeoJsonLineStringTest'             # singolo test
docker compose up -d                                   # Postgres/PostGIS, Redis, MinIO
```

## Review a fine milestone
Catene `/ecc:orchestrate` di sola review, in `docs/MASTER_PLAN.md` §5. Le catene di implementazione (`tdd-guide`) non si usano: il codice lo scrive l'utente.
