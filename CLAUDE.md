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
| Storage | MinIO con pre-signed URL (dipendenza da aggiungere in M3) |
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

**M2 completo** lato codice; restano i checkpoint, che richiedono le migrazioni. Senza le migrazioni V1–V7 l'app non parte comunque.

**Presenti ma non ancora revisionati:** `Dockerfile`, `docker-compose.yml` (vuoto).

**Configurazione:** un solo formato, `.properties` (i file `.yml` sono stati rimossi). I profili si aggiungono come `application-<profilo>.properties`.

**Aperti:**
- `RoleHierarchy`: da verificare che valga anche per `authorizeHttpRequests` e non solo per la method security (prova: token `ADMIN` su una rotta `PARK_MANAGER`).
- `traceId` nell'MDC: lo imposta ancora nessuno. Deciso: `micrometer-tracing-bridge-otel` (aggiunto a M0.1 nel piano).
- `GeoSentinelApplicationTests` è un `@SpringBootTest`: in `./mvnw test` cerca Postgres e fallisce finché mancano DB e migrazioni. Da sostituire con `MigrationIT` (M6.2) o rimuovere.
- `pom.xml`: `groupId` ancora `org.example` (il package è `it.unical.geosentinel`).
- `src/main/resources/static` e `templates`: cartelle vuote del progetto iniziale, da rimuovere.
- `application-local.properties` esiste ma è vuoto (M0.3: flyway con `db/seed`, springdoc attivo, logging SQL).
- Nessun test copre ancora `MaintenanceService` (cache evict su `#result`, confronto di `expectedVersion`): li coprirà `MaintenanceOptimisticLockIT` (M6.2), che richiede V5.

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

**Prossimo passo:** M1.1, migrazioni Flyway V1–V7 (senza, l'app non parte con `ddl-auto=validate`). V1+V2 bastano per far partire `identity`; V3 deve includere le colonne di audit anche su `pois` (`Poi` estende `AuditableEntity`) e `hazard_reports.reporter_id` come `uuid` con FK e indice. Il web di `trail` è fatto: le sue rotte si potranno provare appena c'è V3. Anche `refuge` è completo (web incluso): le sue rotte si provano con V4. Lo stesso vale per `maintenance` con V5 (colonne nel master plan: target multipli, `assigned_to`, `resolved_at`, audit). Resta da scrivere solo `emergency` (M4.3) e `media` (M3). In parallelo restano da chiudere M0.2 (compose + `.env.example`), M0.4 (Dockerfile) e M0.5 (CI).

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
