# GeoSentinel — Master Implementation Plan (Backend)

> Ruolo di Claude: mentore/architetto/reviewer. **Il codice lo scrivi tu.** Qui trovi solo struttura, contratti (firme), vincoli e checkpoint.
> Regola di avanzamento: un task si chiude solo quando **tutti** i suoi checkpoint passano. Poi chiedi la review (vedi §5).
>
> **Stato (17/10): M0–M7 completate.** M7 è il backend per l'app mobile Kotlin Multiplatform (unico client, nessun frontend web), che dal 12/10 è a M9 (`../GeoSentinel-Mobile`): i checkpoint di M7 che richiedevano l'app (push FCM, foto dall'emulatore → `READY`, flusso completo) sono stati provati da lì; restano le push su iOS (APNs, serve un account Apple).
> Dopo M7, su richiesta dell'app (dettagli in `CHANGELOG.md` e negli ADR):
> - ADR-0019: periodo di grazia di 60 s sul refresh token appena ruotato (`V10__refresh_token_successor`);
> - ADR-0020: percorso semplificato nei sentieri della bbox, bbox per `ST_Intersects`;
> - ADR-0021 + audit di sicurezza del 05/10 (7 piste corrette, `docs/security-audit-2026-10.md`): limiti per utente su push SOS e prenotazioni, upload su `staging/`, lock sull'utente nelle operazioni sulle sessioni;
> - rotte `GET /hazards/mine`, `/hazards/nearby`, `/hazards/pending`; rifugi descritti e foto `REFUGE` (`V11__refuge_details`); crediti delle foto esterne (`V12__media_credit`); capacità del rifugio al massimo 1000;
> - dati veri della Calabria da OpenStreetMap e Wikimedia Commons (`tools/osm-import`).
>
> - reset della password con codice via email (M7.2.2, `V13__password_reset_tokens`), completato e verificato il 17/10, da committare.
> **Pianificata (17/10): M8, rischio frane** con un calcolo parallelo in C++ (MPI + pthread) e la feature `landslide` (ADR-0022, ADR-0023).
> M3 e M4 verificate anche dal vivo contro MinIO e Redis del compose, M5.3 nel browser. Limiti noti accettati: vedi le note "Limite noto" in M4.2 e M2.3/M2.7.

---

## 0. Premesse e decisioni architetturali (leggere prima di iniziare)

| # | Decisione | Motivo |
|---|---|---|
| D1 | **Spring Boot 4.1.x** (quello già nel `pom.xml`), non 3.x | Il supporto OSS di Boot 3.5 è terminato a metà 2026. Boot 4 porta: Spring Framework 7, Security 7, Hibernate 7, **Jackson 3** (`tools.jackson.*`), Testcontainers 2.x, starter modulari (`spring-boot-starter-webmvc`, `-flyway`, `-*-test`). Se vuoi davvero 3.5.x, dillo prima di M0: cambiano nomi di starter e package Jackson. |
| D2 | **Hexagonal pragmatico, package-by-feature** | Ogni feature (`trail`, `refuge`, …) ha `domain / application / infrastructure / web`. Le entità JPA **sono** il modello di dominio (niente doppio modello + mapper entity↔domain). Le **porte** esistono solo dove c'è un vero sistema esterno sostituibile: object storage, token blacklist. Niente interfaccia con una sola implementazione per i service. |
| D3 | **JWT via `oauth2-resource-server` (Nimbus), HS256** | Nessun filtro JWT scritto a mano: `BearerTokenAuthenticationFilter` è già nel framework. Tu scrivi: emissione token (`JwtEncoder`), validatore blacklist, converter dei ruoli. Meno codice di sicurezza custom = meno bug di sicurezza. HS256 va bene finché chi firma e chi verifica è lo stesso servizio; se un altro servizio dovrà verificare i token, si passa a RS256/EdDSA con JWKS. |
| D4 | **Refresh token opaco** (random 256 bit), salvato come **hash SHA-256** in Postgres, rotazione con *family* e reuse-detection | Un refresh token rubato e riusato invalida l'intera famiglia. |
| D5 | **Rate limiting: Token Bucket in Redis con script Lua** (atomico, zero dipendenze) | Alternativa: Bucket4j-lettuce se preferisci una libreria. |
| D6 | **Geometrie `geometry(…,4326)` + indice GiST sull'espressione `(col::geography)`** per le query di distanza | `ST_DWithin` su `geometry` 4326 lavora in **gradi**, non metri. Il cast a `geography` dà metri; l'indice sull'espressione evita il seq-scan. |
| D7 | **GeoJSON prodotto da record DTO + MapStruct**, nessuna libreria Jackson-JTS | Le librerie JTS↔Jackson sono per Jackson 2; Boot 4 usa Jackson 3. Due record (`GeoPointDto`, `GeoJsonLineString`) bastano. |
| D8 | **Niente Lombok** | Record per i DTO, getter espliciti nelle entità. Evita i problemi di ordering tra annotation processor con MapStruct. |
| D9 | **UUID come PK** (`GenerationType.UUID`) | ID esposti in API non enumerabili. |
| D10 | **Coordinate: JTS `x = longitude`, `y = latitude`**. Sempre. | È la causa n.1 di bug spaziali. Ci sarà un test di regressione dedicato (M6). |
| D11 | **Password: BCrypt (encoder delegante), lunghezza massima 72 byte UTF-8** | BCrypt usa solo i primi 72 byte e, dalla fix della CVE-2025-22228, Spring Security lancia un'eccezione oltre quella soglia: senza un limite in validazione una password lunga diventa un 500. Il limite è in **byte**, non in caratteri (le lettere accentate valgono 2 byte). Alternativa: Argon2 (`Argon2PasswordEncoder`, richiede Bouncy Castle), che non ha il limite. |
| D12 | **Idempotenza delle POST critiche (`Idempotency-Key`)** | In montagna la rete è instabile e i client ritentano: senza chiave, un retry crea un secondo SOS o una seconda prenotazione. La chiave (UUID generato dal client) è salvata sulla riga con vincolo `UNIQUE (user_id, idempotency_key)`: un retry restituisce la risorsa già creata. Niente Redis: il vincolo del DB è atomico e transazionale. |
| D13 | **Unico client: app mobile Kotlin Multiplatform (Android + iOS)**, nessun frontend web | Niente CORS né cookie: il refresh token resta nel body e l'app lo salva cifrato (Keystore / Keychain). Il contratto dell'API è il documento OpenAPI, da cui si genera il client Kotlin. Cosa serve al backend: M7. |

**Da sistemare subito (stato attuale del repo):**
- ~~`application.properties` → rimuovi `hibernate.dialect=...PostgresSpatialDialect`~~ (fatto).
- ~~Password DB hardcoded~~ → fatto: credenziali da `.env` con le chiavi `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, le stesse lette dall'immagine Postgres. `POSTGRES_DB` contiene il **nome** del database, non l'URL JDBC: l'URL si costruisce in `application.properties`. ~~Aggiungi `.env.example`~~ (fatto in M0.2).
- ~~Base package reale~~ → fatto: `it.unical.geosentinel`.

---

## 1. Struttura del progetto

```
GeoSentinel-Backend/
├── .github/
│   ├── workflows/ci.yml   # verify (unit + IT), docker build, gitleaks
│   └── dependabot.yml
├── docker-compose.yml     # postgres, redis, minio (pgsty), minio-init; backend col profilo "app"
├── Dockerfile
├── .dockerignore
├── .env.example
├── pom.xml
├── CONTRIBUTING.md, SECURITY.md, CHANGELOG.md
├── docker-compose.prod.yml + deploy/Caddyfile   # produzione (M7.6)
├── simulation/            # C++20 + MPI + pthread: landslide-sim, landslide-worker (M8, CMake, Dockerfile proprio)
├── tools/                 # osm-import (dati della Calabria), dem-prep (DEM per M8)
├── docs/
│   ├── README.md          # indice della documentazione
│   ├── MASTER_PLAN.md
│   ├── architecture.md    # arc42 + C4
│   ├── adr/               # Architecture Decision Records (0001–0023 + template)
│   ├── api-guide.md       # guida per chi sviluppa il client
│   ├── operations.md      # runbook
│   └── openapi.json       # contratto (OpenApiContractIT)
└── src/
    ├── main/
    │   ├── java/it/unical/geosentinel/
    │   │   ├── GeoSentinelApplication.java
    │   │   │
    │   │   ├── shared/                               # cross-cutting, nessuna logica di feature
    │   │   │   ├── domain/
    │   │   │   │   ├── AuditableEntity.java          # @MappedSuperclass: id, createdAt/By, updatedAt/By
    │   │   │   │   └── exception/
    │   │   │   │       ├── DomainException.java      # abstract, unchecked
    │   │   │   │       ├── ResourceNotFoundException.java
    │   │   │   │       ├── BusinessRuleViolationException.java
    │   │   │   │       ├── OptimisticConflictException.java
    │   │   │   │       ├── CapacityExceededException.java      # 409 (refuge); qui perché l'handler è in shared
    │   │   │   │       ├── IdempotencyKeyReusedException.java  # 422 (D12)
    │   │   │   │       ├── AlertAlreadyTakenException.java     # 409 (emergency)
    │   │   │   │       ├── MediaRejectedException.java         # 415 / 413 / 422 per motivo (media)
    │   │   │   │       ├── StorageUnavailableException.java    # 503 (MinIO irraggiungibile)
    │   │   │   │       └── RateLimitExceededException.java     # 429 + Retry-After (limite di login per account)
    │   │   │   ├── config/
    │   │   │   │   ├── JpaAuditingConfig.java        # @EnableJpaAuditing + AuditorAware
    │   │   │   │   ├── GeometryConfig.java           # GeometryFactory(SRID 4326) + Clock
    │   │   │   │   ├── AsyncConfig.java              # ThreadPoolTaskExecutor dedicato
    │   │   │   │   ├── CacheConfig.java              # RedisCacheManager tipizzato
    │   │   │   │   ├── SchedulingConfig.java         # @EnableScheduling (pulizia refresh token)
    │   │   │   │   └── OpenApiConfig.java
    │   │   │   └── web/
    │   │   │       ├── GlobalExceptionHandler.java   # @RestControllerAdvice, RFC 7807
    │   │   │       ├── ProblemTypes.java             # costanti URI dei "type"
    │   │   │       ├── ProblemDetailWriter.java      # scrive problem+json dai filtri
    │   │   │       ├── validation/
    │   │   │       │   └── MaxUtf8Bytes.java         # vincolo in byte UTF-8 (D11)
    │   │   │       └── geo/
    │   │   │           ├── GeoPointDto.java
    │   │   │           ├── GeoJsonLineString.java
    │   │   │           └── GeometryMapper.java       # MapStruct: JTS <-> DTO
    │   │   │
    │   │   ├── identity/                             # utenti, auth, sicurezza
    │   │   │   ├── domain/        User, Role, RefreshToken
    │   │   │   ├── application/   AuthService (anche changePassword), UserQueryService (letture per le altre feature),
    │   │   │   │                  UserAccountService + UserProfile (M7.2), RefreshTokenCleanupJob,
    │   │   │   │                  PasswordResetService, PasswordResetMailListener, PasswordResetRequested,
    │   │   │   │                  PasswordResetCleanupJob (M7.2.2),
    │   │   │   │                  port/TokenBlacklist, port/LoginAttemptLimiter, port/PasswordResetLimiter,
    │   │   │   │                  port/PasswordResetMailer
    │   │   │   ├── infrastructure/
    │   │   │   │   ├── persistence/  UserRepository, RefreshTokenRepository, PasswordResetTokenRepository
    │   │   │   │   ├── redis/        RedisTokenBlacklist, RedisRateLimiter, RedisLoginAttemptLimiter,
    │   │   │   │   │                 RedisPasswordResetLimiter, RateLimitPolicy, RateLimitDecision
    │   │   │   │   ├── mail/         SmtpPasswordResetMailer, LoggingPasswordResetMailer (M7.2.2)
    │   │   │   │   └── security/     SecurityConfig, JwtTokenService, JtiBlacklistValidator,
    │   │   │   │                     RateLimitFilter, ProblemAuthenticationEntryPoint,
    │   │   │   │                     ProblemAccessDeniedHandler, JwtProperties, CurrentUser
    │   │   │   └── web/           AuthController, UserController (/users/me, M7.2),
    │   │   │                      PasswordResetController (M7.2.2), dto/* (UserDtos, PasswordReset*Request)
    │   │   │
    │   │   ├── trail/                                # sentieri, POI, segnalazioni pericoli
    │   │   │   ├── domain/        Trail, TrailStatus, Difficulty, Poi, PoiType,
    │   │   │   │                  HazardReport, HazardType, HazardStatus, Severity
    │   │   │   ├── application/   TrailService, PoiService, HazardReportService,
    │   │   │   │                  dto/ TrailCommands, PoiCommands, HazardCommands (command + view record)
    │   │   │   ├── infrastructure/
    │   │   │   │   ├── persistence/  TrailRepository, PoiRepository, HazardReportRepository
    │   │   │   │   └── cache/        TrailCacheConfig (registra la cache "trails" con CacheConfig.typed)
    │   │   │   └── web/           TrailController, PoiController, HazardReportController, dto/* (request + response), dto/TrailMapper
    │   │   │
    │   │   ├── refuge/                               # rifugi e prenotazioni
    │   │   │   ├── domain/        Refuge, Booking, BookingStatus
    │   │   │   ├── application/   RefugeBookingService, dto/RefugeBookingCommands (command + view record + BookingResult, RefugeView con GeoPointDto)
    │   │   │   ├── infrastructure/persistence/  RefugeRepository, BookingRepository
    │   │   │   └── web/           RefugeController (/api/v1/refuges), BookingController (/api/v1/bookings),
    │   │   │                      dto/ RefugeDtos, BookingDtos (request + response), dto/RefugeMapper
    │   │   │
    │   │   ├── maintenance/                          # ticket manutenzione
    │   │   │   ├── domain/        MaintenanceTicket, TicketStatus, TicketPriority
    │   │   │   ├── application/   MaintenanceService, dto/MaintenanceTicketCommands (command + TicketView)
    │   │   │   ├── infrastructure/
    │   │   │   │   ├── persistence/  MaintenanceTicketRepository
    │   │   │   │   └── cache/        MaintenanceCacheConfig (registra "maintenance-by-trail" con CacheConfig.typed)
    │   │   │   └── web/           MaintenanceController (/api/v1/maintenance-tickets), dto/ MaintenanceDtos, MaintenanceMapper
    │   │   │
    │   │   ├── emergency/                            # SOS e squadre di soccorso
    │   │   │   ├── domain/        SosAlert, SosStatus, RescueTeam, TeamStatus, SosAlertRaisedEvent, SosAlertStatusChangedEvent (M7.4)
    │   │   │   ├── application/   SosService, SosDataRetentionJob (GDPR, 90 giorni), dto/SosCommands (command + view + SosRaiseResult)
    │   │   │   ├── infrastructure/
    │   │   │   │   ├── persistence/  SosAlertRepository, RescueTeamRepository
    │   │   │   │   └── notification/ SosNotificationListener (@Async + AFTER_COMMIT, push via DeviceService), SosNotificationRecovery (notified_at)
    │   │   │   └── web/           SosController (/api/v1/sos), RescueTeamController (/api/v1/rescue-teams), dto/ SosDtos, RescueTeamDtos, SosMapper
    │   │   │
    │   │   ├── media/                                # metadati file + MinIO
    │   │   │   ├── domain/        MediaFile, MediaStatus, MediaTargetType, MediaPolicy
    │   │   │   ├── application/   MediaService, MediaCleanupJob, port/ObjectStorage, dto/MediaCommands
    │   │   │   ├── infrastructure/
    │   │   │   │   ├── persistence/  MediaFileRepository
    │   │   │   │   └── storage/      MinioObjectStorage, MinioConfig, MinioProperties
    │   │   │   └── web/           MediaController (/api/v1/media), dto/ MediaDtos, MediaMapper
    │   │   │
    │   │   ├── notification/                         # dispositivi e push (M7.4)
    │   │   │   ├── domain/        DeviceToken, DevicePlatform
    │   │   │   ├── application/   DeviceService, port/PushNotifier (+ record Result, PushMessage)
    │   │   │   ├── infrastructure/
    │   │   │   │   ├── persistence/  DeviceTokenRepository (upsert ON CONFLICT)
    │   │   │   │   └── push/         FcmPushNotifier (push.enabled=true), LoggingPushNotifier (default)
    │   │   │   └── web/           DeviceController (/api/v1/devices), dto/ DeviceDtos
    │   │   │
    │   │   └── landslide/                            # rischio frane (M8, pianificata)
    │   │       ├── domain/        SimulationJob, SimulationType, SimulationStatus, ZoneKind, RiskLevel
    │   │       ├── application/   LandslideRiskService, LandslideResultIngestJob, dto/LandslideCommands
    │   │       ├── infrastructure/  persistence/ (job, rischio per sentiero), event/HazardVerifiedListener
    │   │       └── web/           LandslideRiskController (/api/v1/landslide-risk), dto/ LandslideDtos, LandslideMapper
    │   │
    │   └── resources/
    │       ├── application.properties
    │       ├── application-local.properties
    │       ├── scripts/token_bucket.lua
    │       └── db/
    │           ├── migration/   V1__extensions.sql … V9__device_tokens.sql
    │           └── seed/        R__local_seed.sql   (solo profilo local)
    └── test/
        ├── java/it/unical/geosentinel/
        │   ├── support/AbstractIntegrationTest.java
        │   ├── identity/  AuthServiceLogoutTest, AuthServiceLoginLimitTest, RateLimitFilterTest, AuthFlowIT, RateLimitIT,
        │   │              UserAccountIT, RegistrationAgeIT (M7.2)
        │   ├── trail/     TrailDetailCacheSerializationTest, TrailSpatialQueryIT
        │   ├── refuge/    BookingTest, RefugeBookingServiceTest, BookingControllerTest, RefugeBookingConcurrencyIT
        │   ├── maintenance/ MaintenanceTicketTest, MaintenanceControllerTest, MaintenanceOptimisticLockIT
        │   ├── emergency/ SosAlertTest, SosAssignmentConcurrencyIT, SosNotificationRecoveryIT
        │   ├── notification/ SosPushIT (M7.4)
        │   ├── media/     MediaPolicyTest, MediaServiceTest (+ MediaUploadIT in M7.5)
        │   └── shared/    ProblemDetailWriterTest, GeoJsonLineStringTest, MaxUtf8BytesTest,
        │                  MigrationIT, IdempotencyIT, ProblemDetailContractIT, OpenApiContractIT (M7.1), MobileNetworkIT (M7.3)
        └── resources/application-test.properties   (springdoc acceso solo per OpenApiContractIT)

docs/openapi.json   contratto per il client KMP (M7.1): lo scrive OpenApiContractIT con -Dopenapi.update=true
```

**Regole di dipendenza (hexagonal):**
- `domain` → non importa nulla da `application`, `infrastructure`, `web`, né Spring MVC/Redis/MinIO. (JPA/Jakarta annotations tollerate: decisione D2.)
- `application` → dipende da `domain` e dalle sue `port`. Mai da `web`.
- `infrastructure` → implementa le port, dipende da `domain`/`application`.
- `web` → dipende da `application` e dai DTO. Mai dai repository direttamente.
- Una feature non accede ai repository di un'altra feature: usa il suo service (es. `maintenance` chiama `TrailService.getTrailOrThrow`).

---

## 2. Vincoli trasversali (valgono per ogni task)

- **Stateless singletons:** `@Service`/`@Component` con soli campi `private final` iniettati via costruttore. Zero stato mutabile d'istanza. `GeometryFactory` e `Clock` come bean (immutabili/thread-safe).
- **Transazioni:** `@Transactional` sui metodi dei service applicativi; `readOnly = true` sulle letture. Mai `@Transactional` sui controller.
- **`spring.jpa.open-in-view=false`**: le lazy load fuori transazione devono esplodere in sviluppo, non nascondersi.
- **Relazioni:** `@ManyToOne(fetch = LAZY)` ovunque; niente collezioni bidirezionali se non servono.
- **Enum:** `@Enumerated(EnumType.STRING)` + `CHECK` constraint nel DDL.
- **Tempo:** `Instant` + `timestamptz`; `hibernate.jdbc.time_zone=UTC`; `LocalDate` solo per le date di prenotazione.
- **DTO:** solo `record`, immutabili, con annotazioni JSR-380. Mai esporre entità dai controller.
- **Segreti:** mai nel repo. Solo env var.

---

## 3. Milestone e task

### Milestone 0 — Infrastruttura locale & ambiente

#### M0.1 — `pom.xml`
**Obiettivo:** completare le dipendenze per Boot 4.1; build verde.
**Pattern:** BOM di Spring Boot per le versioni; esplicitare la versione solo dove il BOM non la gestisce.

Aggiungi (quelle già presenti non le ripeto):
- [x] `spring-boot-starter-validation`
- [x] `spring-boot-starter-actuator` (healthcheck per Docker)
- [x] `spring-boot-starter-security-oauth2-resource-server` (nome Boot 4; verifica sul BOM)
- [x] `org.flywaydb:flyway-database-postgresql` (**obbligatorio** da Flyway 10 in poi, altrimenti "Unsupported Database")
- [x] `io.minio:minio` **9.0.3** + `com.squareup.okhttp3:okhttp-jvm` 5.3.2 esplicito: da okhttp 5 l'artefatto `okhttp` è multipiattaforma e con Maven arriva un jar vuoto (le classi sono in `okhttp-jvm`), quindi senza questa riga `NoClassDefFoundError` alla prima chiamata a MinIO. Versione di okhttp allineata a quella dichiarata da minio. La 8.5.x portava `bcprov` 1.78.1 (CVE-2025-8916)
- [x] `org.mapstruct:mapstruct` + `mapstruct-processor` in `maven-compiler-plugin > annotationProcessorPaths`, con `-Amapstruct.defaultComponentModel=spring` e `-Amapstruct.unmappedTargetPolicy=ERROR`
- [x] `org.springdoc:springdoc-openapi-starter-webmvc-ui` (linea 3.x, per Boot 4)
- [x] Test: `spring-boot-testcontainers`, Testcontainers `postgresql` + `junit-jupiter` (Testcontainers 2: artefatti `testcontainers-postgresql`, `testcontainers-junit-jupiter`; verifica sul BOM)
- [x] Plugin `maven-failsafe-plugin` per i test `*IT` separati dagli unit test (`*Test` con surefire)
- [x] Tracing: `spring-boot-micrometer-tracing-opentelemetry` **e** `io.micrometer:micrometer-tracing-bridge-otel` (in Boot 4 il bridge da solo non viene autoconfigurato, e nel modulo di Boot è opzionale: servono entrambi). Mette `traceId`/`spanId` nell'MDC per ogni richiesta, senza filtri scritti a mano; nessun exporter OTLP. Il `traceId` finisce nei ProblemDetail sia da `GlobalExceptionHandler` sia da `ProblemDetailWriter` (401/403/429 dei filtri)

**Checkpoint:**
- [x] `./mvnw -q dependency:tree` senza conflitti evidenti (Jackson 2 solo per springdoc accanto a Jackson 3, `okhttp-jvm` 5.3.2 allineato, `bcprov` 1.84)
- [x] `./mvnw -q compile` verde
- [x] `unmappedTargetPolicy=ERROR` attivo (verificato in M5.2: `RefugeMapper` non si generava con `checkOut` mancante)

#### M0.2 — `docker-compose.yml` + `.env.example`
**Obiettivo:** `docker compose up -d` porta su tutto, healthy, bucket già creato.

Servizi:
- [x] `postgres`: `postgis/postgis:16-3.4-alpine`, credenziali `${POSTGRES_*:?}` (obbligatorie, nessun default debole), volume nominato, porta su `127.0.0.1:${DB_PORT:-5432}` (la stessa `DB_PORT` che usa l'app), healthcheck `pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB`
- [x] `redis`: `redis:7.2-alpine`, `--requirepass ${REDIS_PASSWORD:?}`, healthcheck `redis-cli ping | grep -q PONG` con `REDISCLI_AUTH` (redis-cli esce con 0 anche su `NOAUTH`: il solo exit code non basta)
- [x] `minio`: **`pgsty/minio:RELEASE.2026-08-04T00-00-00Z`**. Le immagini ufficiali `minio/minio` e `quay.io/minio/minio` non sono più scaricabili (repository rimosso / 401): si usa il fork della community pgsty, con tag `RELEASE.*` pinnato. `server /data --console-address :9001`, healthcheck `mc ready local`. La 9000 è esposta su tutte le interfacce (gli URL firmati li usa il telefono), la console solo su localhost
- [x] `minio-init`: `pgsty/mc:RELEASE.2026-09-16T00-00-00Z`, `depends_on: minio: condition: service_healthy`, `set -e`, alias, `mb --ignore-existing` del bucket `${MINIO_BUCKET:-geosentinel-media}` e `anonymous set none` (bucket privato: GET anonimo → 403)
- [x] `backend` facoltativo sotto `profiles: ["app"]` (`docker compose --profile app up -d --build`): `env_file: .env` + `DB_HOST=postgres`, `REDIS_HOST=redis`, `MINIO_ENDPOINT=http://minio:9000`
- [x] `.env.example` con tutte le chiavi, valori finti: `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `MINIO_ROOT_USER/PASSWORD` (le sceglie chi installa: le legge l'immagine MinIO e l'app le usa come access/secret key), facoltative `MINIO_BUCKET`, `MINIO_ENDPOINT` (nel compose `http://minio:9000`), `MINIO_PUBLIC_ENDPOINT` (IP del PC, o `10.0.2.2` per l'emulatore Android), `JWT_SECRET` (≥ 32 byte, base64). Le chiavi si aggiungono quando nasce il servizio che le usa.

**Vincoli:** nessun servizio `app` obbligatorio nel compose di sviluppo (l'app gira dall'IDE); se lo aggiungi, `depends_on` con `condition: service_healthy`.

**Checkpoint:**
- [x] `docker compose ps` → tutti `healthy`, `minio-init` `exited (0)`
- [x] `docker compose exec postgres psql ... "SELECT postgis_lib_version()"` risponde (3.4.3)
- [x] Bucket `geosentinel-media` privato (`mc anonymous get` → `private`, GET anonimo → 403)

#### M0.3 — Configurazione applicativa
Formato scelto: **solo `.properties`** (un unico file base + un file per profilo; mai `.yml` e `.properties` insieme, sulle chiavi duplicate vincerebbe il `.properties` in silenzio).

File:
- [x] `application.properties`: datasource da env (`url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${POSTGRES_DB}`), `ddl-auto=validate`, `open-in-view=false`, `hibernate.jdbc.time_zone=UTC`, Hikari `connection-init-sql=SET lock_timeout = '3s'` (serve in M4: un lock pessimistico non attende all'infinito), `spring.mvc.problemdetails.enabled=true`
- [x] Aggiunti al file base:
  - `spring.data.web.pageable.max-page-size=100`
  - `server.shutdown=graceful` (le richieste in corso finiscono prima dello stop del container)
  - `management.endpoints.web.exposure.include=health` + `management.endpoint.health.show-details=never` (l'actuator non espone altro al pubblico)
  - `springdoc.api-docs.enabled=false` + `springdoc.swagger-ui.enabled=false` (le docs si accendono solo in `local`)
  - `geosentinel.jwt.secret=${JWT_SECRET}`, `access-ttl=15m`, `refresh-ttl=7d`, `session-max-ttl=30d`, `issuer` (M2.2)
- [x] `application-local.properties`: `spring.flyway.locations=classpath:db/migration,classpath:db/seed`, springdoc attivo, logging SQL solo qui
- [x] Record di configurazione `@ConfigurationProperties`: `JwtProperties(secret, accessTtl, issuer)` (usato solo in `infrastructure/security`)
- [x] `MinioProperties(endpoint, publicEndpoint, accessKey, secretKey, bucket)` (`geosentinel.storage.minio.*`), `@Validated`: senza `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` l'avvio fallisce. Endpoint e bucket hanno un default in `application.properties`. I TTL sono costanti in `MediaService` (upload 10 min, download 5 min)
- `refresh-ttl` e `session-max-ttl` **non** stanno in `JwtProperties`: li usa `AuthService`, che sta in `application` e non può dipendere da `infrastructure`, quindi li legge con `@Value`.

**Checkpoint:**
- [x] L'app parte col profilo `local` (con V1–V7 e seed applicati)
- [x] `grep -ri password src/main/resources` → solo placeholder `${...}` (e l'hash BCrypt pubblico del seed)

#### M0.4 — `Dockerfile` multi-stage
- [x] Stage `build`: `eclipse-temurin:21-jdk-alpine`, copia prima `pom.xml` + `.mvn` e fai `dependency:go-offline` (layer cache), poi `src` e `package -DskipTests`
- [x] Estrazione nello stesso stage: `java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted` (Boot 4 ha rimosso `layertools`)
- [x] Stage `runtime`: `eclipse-temurin:21-jre-alpine`, utente non-root, copia i layer in ordine (dependencies → spring-boot-loader → snapshot-dependencies → application) **senza `--chown`** (codice di root, in sola lettura per l'app), `-XX:MaxRAMPercentage=75`
- [x] `.dockerignore`: `.git`, `target/`, `.env*`, `docs/`, `graphify-out/`, IDE

**Checkpoint:**
- [x] `docker build .` verde
- [x] una modifica solo a `src` non invalida il layer delle dipendenze

#### M0.5 — CI e supply chain
**Obiettivo:** ogni push verifica build, test e dipendenze senza intervento manuale.
- [x] `.github/workflows/ci.yml`: job `verify` (JDK 21 Temurin, cache Maven, `chmod +x mvnw` perché è versionato 100644, `./mvnw -B verify`: i runner GitHub hanno Docker, quindi girano anche gli IT con Testcontainers) e job `docker` (`docker build`, niente push). `permissions: contents: read`, `concurrency` che annulla le run superate
- [x] `.github/dependabot.yml`: ecosistemi `maven` (minor/patch raggruppate), `docker`, `docker-compose`, `github-actions`, cadenza settimanale
- [x] Secret scanning: GitHub non lo offre sui repo privati di un account personale (Advanced Security → Secret Protection è a pagamento). Sostituito dal job `secrets` della CI: gitleaks 8.30.1 (binario con checksum verificato) su tutta la storia a ogni push/PR. Storia attuale: 15 commit, nessun segreto. Push protection: non disponibile, il `.env` resta nel `.gitignore`

**Checkpoint:** una PR con un test rotto diventa rossa; Dependabot apre la prima PR.

---

### Milestone 1 — Migrazioni Flyway & entità di persistenza

#### M1.1 — DDL (`src/main/resources/db/migration`)
**Prerequisito:** M0.2 (serve un Postgres/PostGIS acceso per i checkpoint), oppure `MigrationIT` con Testcontainers (M6.2, da anticipare).

**Vincoli:** migrazioni **immutabili** una volta committate (errore = nuova `V` successiva). Ogni FK ha il suo indice. Enum = `varchar` + `CHECK`. Geometrie sempre con tipo e SRID espliciti.
- **V1–V7 tutte prima del primo avvio:** `ddl-auto=validate` controlla **tutte** le entità del persistence unit, non solo quelle della feature: con solo V1+V2 l'app non parte (manca `trails`). Per provarle una alla volta: `MigrationIT`.
- **`validate` controlla solo esistenza e tipo delle colonne**, non `NOT NULL` né lunghezze. Ogni `nullable = false` delle entità va scritto nel DDL, altrimenti il DB accetta `null` senza errori. Colonne `NOT NULL` (oltre a `id`, `created_at`, `version`):
  - `users`: email, password_hash, display_name, role, enabled
  - `refresh_tokens`: user_id, token_hash, family_id, expires_at, family_expires_at
  - `trails`: name, difficulty, status, path · `pois`: name, type, location · `hazard_reports`: reporter_id, type, severity, location, status
  - `refuges`: name, location, capacity, manager_id · `bookings`: refuge_id, user_id, check_in, check_out, guests, idempotency_key, status
  - `maintenance_tickets`: title, priority, status
  - `rescue_teams`: name, call_sign, status, base_location, contact_number · `sos_alerts`: hiker_id, location, location_updated_at, status
  - `media_files`: owner_id, object_key, original_filename, content_type, size_bytes, status

| File | Contenuto |
|---|---|
| `V1__extensions.sql` | `CREATE EXTENSION IF NOT EXISTS postgis;` |
| `V2__users_and_auth.sql` | `users` (id uuid PK, email `varchar(254)`, password_hash, display_name `varchar(100)` (lunghezze allineate a `RegisterRequest` e alle `@Column(length)` di `User`: `validate` non controlla le lunghezze), role CHECK in 4 ruoli, enabled, audit cols, version). **`UNIQUE (email)` + `CHECK (email = lower(email))`**: il service salva e cerca l'email già normalizzata, quindi `WHERE email = ?` usa l'indice unico; il CHECK impedisce righe non normalizzate (es. dal seed). `refresh_tokens` (id, user_id FK, token_hash `varchar(64)` UNIQUE (non `char`: con `ddl-auto=validate` Hibernate si aspetta `varchar` per un `String` e rifiuta `bpchar`), family_id uuid, expires_at, **`family_expires_at`** (scadenza assoluta della sessione, copiata a ogni rotazione), revoked_at NULL, created_at); indici su `user_id`, `family_id`, `family_expires_at` (per la pulizia) |
| `V3__trails_and_refuges.sql` | `trails` (id, name `varchar(150)`, description, difficulty CHECK, status CHECK `OPEN/CLOSED/WARNING`, `path geometry(LineString,4326) NOT NULL`, **`length_m double precision GENERATED ALWAYS AS (ST_Length(path::geography)) STORED`**, audit, version). `pois` (id, trail_id FK NULL, name `varchar(100)`, type CHECK, `location geometry(Point,4326) NOT NULL`, **audit cols**: `Poi` estende `AuditableEntity`, senza `created_at/by` e `updated_at/by` la validazione fallisce all'avvio). `trails.description` è `text`. Le lunghezze di `name` sono allineate ai DTO e alle `@Column(length)` di `Trail`/`Poi` (`validate` non le controlla). `refuges` (id, name `varchar(120)`, `location geometry(Point,4326)`, capacity int `CHECK (capacity > 0)`, manager_id FK, audit). `bookings` (id, refuge_id FK, user_id FK, check_in date, check_out date, guests int `CHECK (guests > 0)`, status CHECK `CONFIRMED/CANCELLED`, `CHECK (check_out > check_in)`, `idempotency_key uuid NOT NULL`, audit); indice `(refuge_id, check_in, check_out)`; **`UNIQUE (user_id, idempotency_key)`** (D12) |
| `V4__hazard_reports.sql` | `hazard_reports` (id, `reporter_id uuid NOT NULL` FK `users(id)`, trail_id FK NULL, type, severity, description `text`, `location geometry(Point,4326) NOT NULL`, status, audit); indici su `reporter_id` e `trail_id`, **GiST su `location` e su `(location::geography)`** (li usa `findActiveNearTrail`) |
| `V5__maintenance.sql` | `maintenance_tickets` (id, title `varchar(150)`, description `text`, priority CHECK `LOW/MEDIUM/HIGH/CRITICAL`, status CHECK `OPEN/ASSIGNED/IN_PROGRESS/RESOLVED/CANCELLED`, trail_id FK NULL, refuge_id FK NULL, hazard_report_id FK NULL, `CHECK (num_nonnulls(trail_id, refuge_id, hazard_report_id) >= 1)` (un ticket da segnalazione ha anche il sentiero della segnalazione), assigned_to FK `users(id)` NULL, resolved_at NULL, **`version bigint NOT NULL DEFAULT 0`**, audit cols (`MaintenanceTicket` estende `AuditableEntity`)); indici su `(trail_id, created_at)`, `refuge_id`, `hazard_report_id`, `assigned_to`, `(status, priority)` |
| `V6__emergency.sql` | `rescue_teams` (id, name `varchar(100)` UNIQUE, call_sign `varchar(32)`, contact_number `varchar(30)`, status CHECK `AVAILABLE/ON_MISSION/OFF_DUTY`, `base_location geometry(Point,4326)`, audit cols). `sos_alerts` (id, hiker_id FK `users(id)`, `idempotency_key uuid NULL`, `location geometry(Point,4326) NOT NULL`, `location_updated_at NOT NULL`, medical_notes `text`, status CHECK `ACTIVE/DISPATCHED/RESOLVED/CANCELLED`, assigned_team_id FK `rescue_teams(id)` NULL, operator_id FK `users(id)` NULL, closed_at NULL, `notified_at timestamptz NULL`, `anonymized_at timestamptz NULL`, audit cols; **niente `version`**: le scritture passano da lock pessimistici). **Indice parziale** su `(created_at) WHERE status IN ('ACTIVE','DISPATCHED')` per la dashboard; **`UNIQUE (hiker_id, idempotency_key)`** (D12; i `NULL` non collidono); **`UNIQUE (hiker_id) WHERE status IN ('ACTIVE','DISPATCHED')`** (al più un'allerta attiva per escursionista, rete di sicurezza dell'advisory lock); indice parziale `(created_at) WHERE notified_at IS NULL` (recupero notifiche); indice parziale `(closed_at) WHERE anonymized_at IS NULL` (conservazione); indici su `assigned_team_id`, `operator_id` |
| `V7__media.sql` | `media_files` (id, owner_id FK `users(id)`, object_key `varchar(64)` UNIQUE, original_filename `varchar(255)`, content_type `varchar(50)`, size_bytes bigint (dichiarata, poi reale dopo il confirm), status `varchar(20)` CHECK `PENDING_UPLOAD/READY/QUARANTINED`, entity_type `varchar(20)` CHECK `TRAIL/HAZARD_REPORT` NULL, entity_id uuid NULL (polimorfico: niente FK, l'esistenza la verifica `MediaService`), `CHECK ((entity_type IS NULL) = (entity_id IS NULL))`, checksum `varchar(64)`, `version bigint NOT NULL DEFAULT 0`, audit cols (`MediaFile` estende `AuditableEntity`)); indici `(owner_id, status, created_at)` (quota e `/my`), `(entity_type, entity_id) WHERE status = 'READY'`, `(created_at) WHERE status = 'PENDING_UPLOAD'` (`MediaCleanupJob`) |
| `V8__users_account.sql` (M7.2) | `users.deleted_at timestamptz NULL` + indice parziale `WHERE deleted_at IS NOT NULL`, `users.age_confirmed_at timestamptz NULL` (la data di nascita non si salva) |
| `V9__device_tokens.sql` (M7.4) | `device_tokens` (id, user_id FK `users(id)`, `token varchar(1024)` UNIQUE, platform CHECK `ANDROID/IOS`, `last_seen_at`, audit cols); indice su `user_id`. 1024 e non 4096: un indice B-tree rifiuta righe oltre ~2700 byte |

**Indici spaziali (in ciascuna migrazione, accanto alla tabella):**
- `USING GIST (path)` / `USING GIST (location)`: per `ST_Contains`, `ST_Intersects`, bbox `&&`
- `USING GIST ((path::geography))` / `USING GIST ((location::geography))`: per `ST_DWithin` in metri (decisione D6)

- [x] `db/seed/R__local_seed.sql` (repeatable, **solo profilo local**, `ON CONFLICT DO NOTHING`): un utente per ruolo + un secondo HIKER (password `Password123!`, hash **`{bcrypt}…`**: il `DelegatingPasswordEncoder` senza prefisso non riconosce l'hash), 2 sentieri e 2 POI in Sila, 1 rifugio con capacity 4 + una prenotazione, 2 squadre, una segnalazione con il suo ticket, un SOS attivo non notificato (sul secondo HIKER). Niente media: senza l'oggetto nel bucket il download risponderebbe 404
- Gli `id` non hanno `DEFAULT`: li genera Hibernate (il seed usa UUID fissi). Nessuna estensione oltre a PostGIS

**Checkpoint:**
- [x] App avviata: `flyway_schema_history` contiene V1–V7 con `success = true` (+ il seed)
- [x] `EXPLAIN` di una query `ST_DWithin(path::geography, …)` su `trails` mostra `Index Scan using idx_trails_path_geog`
- [x] `SELECT length_m FROM trails` restituisce metri plausibili per il seed (3193 m e 4525 m)

#### M1.2 — Base di persistenza e auditing
File + contratti:
- [x] `AuditableEntity` (`@MappedSuperclass`, `@EntityListeners(AuditingEntityListener.class)`): `UUID id` (`@Id @GeneratedValue(strategy = UUID)`), `@CreatedDate Instant createdAt`, `@LastModifiedDate Instant updatedAt`, `@CreatedBy UUID createdBy`, `@LastModifiedBy UUID updatedBy`
- [x] `JpaAuditingConfig`: `@EnableJpaAuditing(auditorAwareRef = …, dateTimeProviderRef = …)`
  - `AuditorAware<UUID> auditorAware()`: legge il `sub` del `Jwt` dal `SecurityContext`; `Optional.empty()` se anonimo
  - `DateTimeProvider dateTimeProvider(Clock clock)`: così i test controllano il tempo
- [x] `GeometryConfig`: `GeometryFactory geometryFactory()` → `new PrecisionModel()`, SRID 4326; `Clock clock()` → UTC
- [x] `equals/hashCode` delle entità: basati su `id`, con `id != null` e confronto per classe effettiva (proxy Hibernate). `hashCode` costante per classe.

#### M1.3 — Entità di dominio
**Pattern:** *rich domain model*: le transizioni di stato vivono nell'entità e lanciano `BusinessRuleViolationException` se illegali. Setter pubblici solo dove non esiste una regola. Costruttore `protected` no-arg per JPA + factory/costruttore pubblico con gli invarianti.

Firme chiave (solo contratti):
- [x] `Trail`: `LineString path`; `void changeStatus(TrailStatus newStatus)`; `void updateDetails(String name, String description, Difficulty difficulty, LineString path)`; `@Version`
  - `length_m` mappato `@Generated(event = {INSERT, UPDATE})` + `@Column(insertable = false, updatable = false)`: senza `@Generated` Hibernate non rilegge la colonna generata, e il valore resta `null` dopo la creazione e vecchio dopo un cambio di path (finendo così nella cache `trails`)
  - Regola di dominio: `CLOSED → WARNING` vietato (`BusinessRuleViolationException`), un sentiero chiuso si riapre prima (`OPEN`). Stesso stato → no-op
  - Invarianti: SRID 4326, almeno 2 punti
- [x] `Poi`: `static create(name, type, location)` (POI indipendente) / `createForTrail(trail, name, type, location)`; `update(...)`; `assignToTrail(Trail)`; `detachFromTrail()`. SRID 4326 verificato. Nessuna transizione di stato: `verify/reject/resolve` valgono solo per `HazardReport`
- [x] `HazardReport`: `void verify()` / `void reject()` (solo da `SUBMITTED`), `void resolve()` (solo da `VERIFIED`); `assignToTrail(Trail)`
  - Il segnalatore è `UUID reporterId`, **non** `@ManyToOne User`: `trail` non dipende dal dominio di `identity` e non usa `UserRepository`. L'id arriva dal `sub` del JWT; la FK `reporter_id → users(id)` resta nel DDL. Stesso schema di `Booking.userId`
- [x] `Refuge`: `int getCapacity()`; `void changeCapacity(int newCapacity)`; `boolean isManagedBy(UUID userId)`
- [x] `Booking`: `static Booking create(Refuge refuge, UUID userId, UUID idempotencyKey, LocalDate checkIn, LocalDate checkOut, int guests)`; `boolean isSameRequest(...)`; `void cancel(Instant now)` (la data si ricava da `now`, mai `LocalDate.now()`). `checkOut > checkIn` come il CHECK del DDL
- [x] `MaintenanceTicket` (estende `AuditableEntity`, `@Version Long version`): factory `forTrail` / `forRefuge` / `forHazard` (eredita il sentiero della segnalazione); `updateDetails(title, description, priority)`; `assign(UUID)` (da `OPEN` → `ASSIGNED`, una riassegnazione di un `IN_PROGRESS` non cambia stato); `startWork()`, `resolve(Instant now)` (da `ASSIGNED`/`IN_PROGRESS`), `cancel()` (non da `RESOLVED`). Test: `MaintenanceTicketTest`
- [x] `SosAlert` (stati `ACTIVE → DISPATCHED → RESOLVED`, `CANCELLED` da attivo): `static raise(hikerId, idempotencyKey, location, medicalNotes, now)`; `dispatch(RescueTeam, UUID operatorId)` (solo da `ACTIVE`; già `DISPATCHED` → `AlertAlreadyTakenException` 409); `resolve(Instant now)` e `cancel(Instant now)` (impostano `closedAt` e liberano la squadra); `updateLocation(Point, Instant now)` (solo se attiva, aggiorna `locationUpdatedAt`). Niente passo `IN_CHARGE` separato: l'esclusività è sul dispatch e `operatorId` registra chi l'ha fatto. `notified_at`/`anonymized_at` non sono mappati (li scrivono solo query dedicate, un flush dell'entità non li sovrascrive). Test: `SosAlertTest`
- [x] `RescueTeam`: `deploy()` (`AVAILABLE → ON_MISSION`), `release()` (da `ON_MISSION`), `setOffDuty()` (non in missione), `setAvailable()` (non da `ON_MISSION`: la squadra si libera solo chiudendo l'allerta)
- [x] `User`, `RefreshToken`, `MediaFile`: vedi M2/M3

**Vincoli JTS:** ogni `Point`/`LineString` creato **solo** dal `GeometryFactory` bean (SRID corretto). Mai `new Coordinate(lat, lon)`: è `new Coordinate(lon, lat)`.

**Checkpoint M1:**
- [x] L'app parte con `ddl-auto=validate` → nessun mismatch colonna/tipo
- [x] Unit test `SosAlertTest`: transizioni legali OK, illegali lanciano (nessun Spring, JUnit puro)

---

### Milestone 2 — Sicurezza & autenticazione

#### M2.1 — Utente e password
- [x] `Role` enum: `HIKER, PARK_MANAGER, RESCUE_OPERATOR, ADMIN`
- [x] `User` entity (email normalizzata lowercase nel costruttore)
- [x] `UserRepository`: `Optional<User> findByEmail(String email)`; `boolean existsByEmail(String email)` (email già normalizzata da `User.normalizeEmail`; `IgnoreCase` genera `upper(email)` e non userebbe l'indice)
- [x] Bean `PasswordEncoder` = `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (BCrypt, prefisso `{bcrypt}`)
- [x] Validatore custom `@MaxUtf8Bytes(72)` in `shared/web/validation/MaxUtf8Bytes.java` (annotazione + `ConstraintValidator` annidato in un solo file, `value.getBytes(UTF_8).length <= max`, `null` valido) sulla password di register e login (D11). Test: `MaxUtf8BytesTest`. `@Size` conta i caratteri, non i byte: da solo non basta.

#### M2.2 — Emissione e validazione JWT
**Pattern:** resource server nativo (D3). Claims dell'access token: **solo** `iss`, `sub` (userId), `roles` (lista), `jti` (UUID random), `iat`, `exp` (**15 min**). Niente dati personali (email, displayName): il JWT è leggibile da chiunque lo intercetti.

- [x] `JwtTokenService implements AuthService.JwtIssuer`:
  - `IssuedAccessToken issueAccessToken(User user)` → record `(String tokenValue, String jti, Instant expiresAt)`
  - `AuthService` sta in `application` e non può dipendere da `infrastructure`: dichiara l'interfaccia `JwtIssuer` (un solo metodo) che `JwtTokenService` implementa
- [x] `SecurityConfig` (bean):
  - `JwtEncoder jwtEncoder()`: `NimbusJwtEncoder` con `ImmutableSecret`, header `HS256` esplicito
  - `JwtDecoder jwtDecoder(JtiBlacklistValidator v)`: `NimbusJwtDecoder.withSecretKey(...).macAlgorithm(HS256)` + `DelegatingOAuth2TokenValidator(JwtValidators.createDefaultWithIssuer(iss), v)`
  - `JwtAuthenticationConverter`: authorities dal claim `roles`, prefisso `ROLE_`
  - `RoleHierarchy`: `ADMIN > RESCUE_OPERATOR`, `ADMIN > PARK_MANAGER`, `PARK_MANAGER > HIKER`, `RESCUE_OPERATOR > HIKER`
  - `SecurityFilterChain`: `csrf` off, `sessionManagement STATELESS`, `oauth2ResourceServer(jwt)`, entry point e access-denied handler custom (M2.5), regole rotta (tabella sotto)
  - CORS: **nessuna** configurazione finché non esiste un frontend web (il default nega le richieste cross-origin). Quando arriva: `CorsConfigurationSource` con origini esplicite da property, mai `*` insieme alle credenziali
  - `@EnableMethodSecurity`
- [x] `CurrentUser`: helper per estrarre `UUID userId` dal `Jwt` (evita di ripetere il parsing in ogni controller); `boolean isAdmin()` (authority `ROLE_ADMIN` dal claim `roles`: la `RoleHierarchy` vale solo nelle espressioni di sicurezza, non in un controllo scritto a mano). Il controller passa il booleano al service, che resta testabile senza `SecurityContext`

| Rotta | Accesso |
|---|---|
| `POST /api/v1/auth/register`, `/login`, `/refresh` | permitAll |
| `POST /api/v1/auth/logout` | `authenticated()`: senza access token non si può mettere il `jti` in blacklist |
| `POST /api/v1/auth/password-reset/request`, `/confirm` (M7.2.2) | permitAll, `Authorization` ignorato come su login (`TOKENLESS_AUTH_PATHS`) |
| `/api/v1/users/me/**` (M7.2) | `authenticated()`; `DELETE` solo HIKER, il personale riceve 409 `account-managed` (controllo nel service) |
| `GET /api/v1/trails/**`, `GET /api/v1/pois/**` | permitAll (informazioni di sicurezza pubbliche) |
| `/v3/api-docs/**`, `/swagger-ui/**`, `/actuator/health` | permitAll (docs solo in `local`) |
| tutto il resto | `authenticated()` + `@PreAuthorize` sui service |

**Vincolo:** il segreto HMAC deve essere ≥ 256 bit; all'avvio fallisci (fail-fast) se è più corto. Il controllo va fatto sui **byte** (`secret.getBytes(UTF_8).length >= 32`) nel costruttore compatto di `JwtProperties`: `@Size` conta i caratteri.

**Trappola:** dentro `filterChain(...)` non richiamare i metodi `@Bean` (`jwtDecoder(...)`, `jwtAuthenticationConverter()`): si otterrebbero istanze nuove, diverse da quelle registrate. I bean si ricevono come **parametri del metodo**.

#### M2.3 — Refresh token con rotazione
**Pattern:** token opaco + famiglia + reuse detection (D4).
- [x] `RefreshToken` entity: `tokenHash`, `familyId`, `expiresAt`, `familyExpiresAt`, `revokedAt`
- [x] `RefreshTokenRepository`:
  - `Optional<RefreshToken> findByTokenHash(String hash)`
  - `@Modifying int revokeIfActive(String hash, Instant now)` → `UPDATE … SET revoked_at = :now WHERE token_hash = :hash AND revoked_at IS NULL` (**atomico**: rowcount 1 = hai vinto la rotazione; 0 = token già usato → reuse)
  - `@Modifying int revokeFamily(UUID familyId, Instant now)`
  - `@Modifying int deleteExpired(Instant cutoff)` → cancella i token con **`family_expires_at < :cutoff`** (sessione scaduta), non `expires_at`: finché la sessione vive un token vecchio deve restare, altrimenti il suo riuso (es. il client legittimo che torna dopo 8 giorni con un token rubato e già ruotato) non viene riconosciuto e la famiglia dell'attaccante resta attiva. Tutti i token di una famiglia scaduta sono scaduti (`expiresAt ≤ familyExpiresAt`)
- [x] `AuthService`:
  - `AuthTokens register(RegisterCommand cmd)` → ruolo sempre `HIKER` (i ruoli elevati solo via seed/admin). Email già registrata → 422 `business-rule` (il 409 resta per la race tra due registrazioni simultanee, dal vincolo UNIQUE): **rivela che l'account esiste**. Scelta accettata (UX più semplice, il rate limit su `/auth/**` limita la raccolta massiva); scrivila in un commento. L'alternativa senza fuga richiede la verifica via email.
  - `AuthTokens login(String email, String rawPassword)` → stesso errore generico per email inesistente e password errata, **e stesso tempo di risposta**: se l'utente non esiste, esegui comunque `passwordEncoder.matches(rawPassword, DUMMY_HASH)` (hash BCrypt costante calcolato una volta). Senza, la risposta più veloce rivela che l'email non è registrata.
  - `AuthTokens refresh(String rawRefreshToken)` → hash; `revokeIfActive`; se 0 e il token esiste → `revokeFamily` + 401; se `familyExpiresAt` è passato → 401 (serve un nuovo login); altrimenti nuovo refresh con **stesso** `familyId` e **stesso** `familyExpiresAt`. **`@Transactional(noRollbackFor = BadCredentialsException.class)`**: il 401 è una `RuntimeException` e annullerebbe la revoca della famiglia appena fatta, vanificando la reuse detection
  - `void logout(Jwt accessToken, String rawRefreshToken)` → blacklist del `jti` + revoca della famiglia **solo se il refresh token appartiene al `sub` del JWT** (un token altrui si ignora in silenzio: 204 comunque, nessuna rivelazione; test `AuthServiceLogoutTest`). **`noRollbackFor = IllegalStateException.class`**: se Redis è giù la blacklist fallisce, ma la revoca della famiglia nel DB deve restare
  - Il token nuovo scade al più tardi con la sessione: `refreshExpiresAt = min(now + refreshTtl, familyExpiresAt)`
- [x] Durate: access 15 min (`JwtProperties.accessTtl`), refresh 7 giorni scorrevole (`geosentinel.jwt.refresh-ttl`), sessione massima 30 giorni (`geosentinel.jwt.session-max-ttl` → `familyExpiresAt`, assoluta). Le ultime due entrano in `AuthService` con `@Value` (vedi M0.3)
- [x] Pulizia: `RefreshTokenCleanupJob` (`identity/application`), `@Scheduled(cron = "0 30 3 * * *", zone = "UTC")` che chiama `deleteExpired(now)`; `@EnableScheduling` in `shared/config/SchedulingConfig`. Nessun margine: dopo `familyExpiresAt` il riuso non ha più nulla da revocare. Con più istanze gira su ognuna (DELETE idempotente).
- [x] Generazione token: `SecureRandom` 32 byte → Base64URL; hash `SHA-256` hex (`MessageDigest`, stdlib)

#### M2.4 — Blacklist Redis (porta + adapter)
- [x] Porta `identity.application.port.TokenBlacklist`:
  - `void revoke(String jti, Duration ttl)`
  - `boolean isRevoked(String jti)`
- [x] `RedisTokenBlacklist implements TokenBlacklist`: chiave `bl:jti:{jti}`, TTL = `exp - now` (la chiave scade con il token, nessuna pulizia manuale)
- [x] `JtiBlacklistValidator implements OAuth2TokenValidator<Jwt>` → `OAuth2TokenValidatorResult.failure(...)` se revocato

**Decisione da prendere (fail-open vs fail-closed):** se Redis è giù, `isRevoked` fallisce. Raccomandato **fail-closed** (401) per le rotte SOS/admin; documenta la scelta nel codice.

#### M2.5 — Errori di sicurezza in RFC 7807
I 401/403 generati nei filtri **non passano** da `@RestControllerAdvice`.
- [x] `ProblemDetailWriter`: `void write(HttpServletResponse res, ProblemDetail pd)` → `application/problem+json`, usa l'`ObjectMapper`/`JsonMapper` del contesto
- [x] `ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint` → 401 + header `WWW-Authenticate: Bearer`
- [x] `ProblemAccessDeniedHandler implements AccessDeniedHandler` → 403
- **Vincoli:** messaggio **generico**, mai `authException.getMessage()` (direbbe a chi attacca se è la firma, la scadenza o il `jti` revocato); `timestamp` dal bean `Clock`, non da `Instant.now()`.
- [x] `GlobalExceptionHandler`: handler per `AuthenticationException` → 401. Le eccezioni lanciate dai **service** (`login`/`refresh`) non passano dai filtri: senza questo handler finivano nel fallback `Exception` → 500.

#### M2.6 — Rate limiting distribuito
**Pattern:** Token Bucket atomico in Redis via script Lua (D5).
- [x] `resources/scripts/token_bucket.lua`: input `KEYS[1]`, `ARGV = capacity, refillPerSecond, requested`; l'orario lo legge lo script con `redis.call("TIME")` (un solo orologio per tutte le istanze); salva `tokens` + `ts` in un hash; ritorna `{allowed(0/1), remaining, retryAfterMillis}`; imposta `PEXPIRE` sulla chiave
- [x] `RedisRateLimiter`: `RateLimitDecision tryConsume(String key, RateLimitPolicy policy)`; `DefaultRedisScript` caricato **una volta** (bean/field final); rifiuta `requestedTokens` fuori da `1..capacity` (oltre la capacità il bucket non si riempie mai → `Retry-After` infinito)
- [x] `RateLimitPolicy(capacity, refillPerSecond, failClosed)`: valida `capacity > 0` e `refillPerSecond > 0` (refill a zero = divisione per zero nello script). Con Redis irraggiungibile: **fail-open** di default, **fail-closed** per le policy create con `.withFailClosed()` (solo `/auth/**`, altrimenti un guasto di Redis toglie la protezione dal brute force)
- [x] `RateLimitFilter extends OncePerRequestFilter`, registrato **dopo** `BearerTokenAuthenticationFilter`:
  - chiave = `{prefisso}user:{sub}` se autenticato, altrimenti `{prefisso}ip:{remoteAddr}`, con prefisso `rl:auth:` per `/api/v1/auth/**` e `rl:` per il resto. **Un bucket per policy:** con una chiave condivisa le GET pubbliche (policy larga) ricaricherebbero il bucket del login, aggirando il limite di 5 al minuto
  - policy più stretta per `/api/v1/auth/**` (anti brute-force), es. 5 req/min per IP, **fail-closed** (`RateLimitPolicy.perMinute(5).withFailClosed()`): con Redis giù → 429. Le altre policy sono fail-open
- [x] Limite **per account** sul login, dentro `AuthService.login` (il filtro non legge il body): chiave `rl:login:{sha256(email normalizzata)}`, 10 tentativi / 15 min, fail-closed. Porta `identity.application.port.LoginAttemptLimiter` + adapter `RedisLoginAttemptLimiter` (riusa `RedisRateLimiter`); controllo **prima** di `findByEmail` e BCrypt; `RateLimitExceededException` (`shared/domain/exception`) → 429 in `GlobalExceptionHandler`. Test: `AuthServiceLoginLimitTest`. Limite accettato: chi attacca può rallentare il login di una vittima (il bucket ricarica un tentativo ogni 90 s). Il limite per IP non ferma il credential stuffing, che arriva da molti IP; questo sì. Chiave hashata: niente email in chiaro su Redis. Stesso 429 sia che l'account esista sia che no (niente enumerazione).
  - se negato: 429 + `Retry-After` + problem+json con `type` da `ProblemTypes.RATE_LIMIT_EXCEEDED` (mai un URI scritto a mano)
  - headers `X-RateLimit-Remaining`
  - `timestamp` dal bean `Clock`; il filtro si istanzia a mano in `SecurityConfig` (niente `@Component`, che lo registrerebbe anche come filtro servlet fuori dalla catena di sicurezza)
- **Vincolo:** usa `request.getRemoteAddr()`; `X-Forwarded-For` solo con `server.forward-headers-strategy=native` dietro un proxy fidato.

#### M2.7 — `AuthController`
- [x] `POST /api/v1/auth/register` → 201 `TokenResponse`
- [x] `POST /api/v1/auth/login` → 200 `TokenResponse(accessToken, accessExpiresAt, refreshToken, tokenType="Bearer")`
- [x] `POST /api/v1/auth/refresh` → 200 `TokenResponse`
- [x] `POST /api/v1/auth/logout` → 204 (autenticato). Body `RefreshRequest` **facoltativo** (`@RequestBody(required = false)`): senza refresh token si mette comunque in blacklist il `jti`
- [x] DTO: `RegisterRequest(@Email @NotBlank @Size(max=254) email, @Size(min=12) @MaxUtf8Bytes(72) password, @NotBlank @Size(2..100) displayName)`, `LoginRequest` (sulla password del login **solo** `@NotBlank` + `@MaxUtf8Bytes(72)`, niente `min`: le regole di composizione valgono alla registrazione), `RefreshRequest(@NotBlank refreshToken)`
- **Trasporto del refresh token:** nel body JSON, adatto a un client mobile. Se arriverà una SPA nel browser, va spostato in un cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` (fuori dalla portata di JavaScript/XSS), riattivando la protezione CSRF su quelle rotte.

**Checkpoint M2:**
- [x] register → chiamata protetta 200 → logout → stessa chiamata 401 problem+json (`AuthFlowIT`)
- [x] refresh due volte con lo **stesso** token: la seconda → 401 e **anche** il token nuovo della prima rotazione è revocato (`AuthFlowIT`)
- [x] refresh oltre `familyExpiresAt` → 401 anche con un token mai usato (`AuthFlowIT`)
- [x] registrazione con password di 80 caratteri ASCII → 400 (non 500) (`AuthFlowIT`)
- [x] access token scaduto → 401 (`AuthFlowIT`: token firmato con `exp` nel passato, oltre i 60 s di tolleranza)
- [x] 6 login rapidi dallo stesso IP → il 6° riceve 429 con `Retry-After` (`RateLimitIT`)
- [x] HIKER su una rotta `PARK_MANAGER` → 403 problem+json; ADMIN passa grazie alla `RoleHierarchy` (`AuthFlowIT`)

---

### Milestone 3 — Object storage (MinIO, presigned POST policy)

**Flusso (nessun byte passa dal backend):**
`POST /media/upload-requests` → validazione (tipo, dimensione, target, quota) → riga `PENDING_UPLOAD` + **presigned POST policy** → il client fa un POST multipart su MinIO → `POST /media/{id}/confirm` → il backend fa `statObject` + lettura dei primi byte → `READY` (oppure `QUARANTINED` + delete dell'oggetto) → `GET /media/{id}/download-url` → URL GET firmato.

**Perché POST policy e non PUT pre-firmato:** un PUT pre-firmato firma solo l'host, quindi il client può caricare un file di qualunque dimensione e tipo; se poi non chiama mai il confirm, l'oggetto resta nel bucket fuori da ogni quota. La policy (`content-length-range` 1..dimensione dichiarata, `eq $Content-Type`, `eq $key`) la fa rispettare MinIO.

#### M3.1 — Porta ObjectStorage + adapter MinIO
- [x] Porta `media.application.port.ObjectStorage` (il bucket è un dettaglio dell'adapter):
  - `PresignedUpload presignUpload(String objectKey, String contentType, long maxBytes, Duration ttl)` → record `(String url, Map<String,String> formFields)`: il client invia tutti i campi e per ultimo `file`
  - `String generatePresignedDownloadUrl(String objectKey, String contentType, Duration ttl)`
  - `Optional<StoredObject> stat(String objectKey)` → record `(long sizeBytes, String etag)`, vuoto se l'oggetto non esiste
  - `byte[] readHead(String objectKey, int length)` (GET con range, per i magic bytes)
  - `void deleteObject(String objectKey)`
- [x] `MinioObjectStorage implements ObjectStorage`: **due** `MinioClient` costruiti nell'adapter, uno per le operazioni server-side (`endpoint`) e uno **solo per firmare** con `publicEndpoint` (la firma include l'host: un URL firmato con l'host interno Docker non funziona dal client). Region fissa `us-east-1` (senza, il client la chiede al server prima di firmare), timeout brevi (connect 5 s, read/write 30 s; il default MinIO è 5 minuti, dentro una transazione che tiene una connessione del pool). Traduce `MinioException` in `StorageUnavailableException` (→ 503 + `Retry-After`, detail generico). API di MinIO 9: `io.minio.Http.Method`, non più `io.minio.http.Method`
- [x] `MinioConfig`: solo `@EnableConfigurationProperties(MinioProperties.class)`. Il bucket lo crea `minio-init` nel compose (privato): niente chiamate di rete all'avvio del contesto

#### M3.2 — Policy e metadati
- [x] `MediaPolicy` (valore di dominio, puro, unico punto delle regole: il DTO controlla solo la presenza dei campi): whitelist `image/jpeg, image/png, image/webp`, max **10 MB**, quota utente **1 GB**; `matchesSignature(contentType, head)` (JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A`, WebP `RIFF….WEBP`, 12 byte letti). Violazioni → `MediaRejectedException(reason)` → 415 `media-unsupported-type` / 413 `media-payload-too-large` / 422 `media-quota-exceeded`. Il nome evita lo scontro con `org.springframework.web.server.PayloadTooLargeException` negli auto-import. Video (`video/mp4`) rimandati
- [x] `MediaFile` (estende `AuditableEntity`, `@Version`): `initUpload(ownerId, objectKey, originalFilename, contentType, sizeBytes, entityType, entityId)` (policy + `entityType`/`entityId` entrambi o nessuno); `markReady(actualSize, checksum)` e `quarantine()` solo da `PENDING_UPLOAD`; `isOwnedBy(UUID)`. `size_bytes` è quella dichiarata finché il file è `PENDING_UPLOAD`, poi quella reale
- [x] `MediaStatus`: `PENDING_UPLOAD`, `READY`, `QUARANTINED` (oggetto cancellato, riga tenuta per traccia)
- [x] `MediaTargetType`: `TRAIL`, `HAZARD_REPORT` (enum, non stringa libera). `entity_id` è polimorfico: niente FK, l'esistenza la verifica il service
- [x] `MediaFileRepository`: `sumQuotaBytes(ownerId, pendingSince, READY, PENDING_UPLOAD)` (JPQL con gli enum come parametri); `lockOwner(namespace, key)` (`pg_advisory_xact_lock(int, int)`: la forma a due chiavi int è un keyspace separato dal lock bigint degli SOS); `findByStatusAndCreatedAtBefore(…, Pageable)` e `deleteIfPending(id, PENDING_UPLOAD)` per la pulizia

#### M3.3 — `MediaService` e `MediaCleanupJob`
- [x] `UploadSessionView initiateUpload(UUID ownerId, InitUploadCommand cmd)` → `(mediaId, uploadUrl, formFields, expiresAt)`:
  1. `MediaFile.initUpload` (policy) → verifica del target: `TRAIL` deve esistere (`TrailService.getTrailOrThrow`), `HAZARD_REPORT` deve esistere ed essere del segnalatore (`HazardReportService.getHazardOrThrow`, 404 altrimenti)
  2. `lockOwner` → `sumQuotaBytes` → `checkQuota` (il lock viene prima della lettura: due richieste simultanee non superano la quota)
  3. save → `presignUpload` con `maxBytes` = dimensione dichiarata
  - `@PreAuthorize`: le foto di un **sentiero** sono contenuto ufficiale visibile a tutti → solo `PARK_MANAGER`; un escursionista allega foto alle **proprie** segnalazioni
- [x] `MediaFileView confirmUpload(UUID ownerId, UUID mediaId)`: 404 se non tuo; già `READY`/`QUARANTINED` → restituisce l'esito (retry); oggetto assente → 422; size ≤ dichiarata (controllo ripetuto per sicurezza) **e** magic bytes coerenti con il tipo → `markReady`; altrimenti `quarantine` + delete dell'oggetto. Il `Content-Type` salvato da MinIO è quello dichiarato dal client, non una prova del contenuto
- [x] Visibilità (`getMedia`, `getPresignedDownloadUrl`): il proprietario vede tutti i suoi file; gli altri solo i `READY` allegati a un'entità **che possono vedere**. I sentieri sono pubblici; per le segnalazioni si riusa il `@PostAuthorize` di `HazardReportService.getHazard` (una `REJECTED` la vedono solo `PARK_MANAGER` e il segnalatore): unica fonte della regola. 404, mai 403. `getAttachedMedia` applica lo stesso controllo sul target
- [x] Download: solo `READY`; l'URL firmato forza `response-content-type` (MIME validato) e `response-content-disposition=attachment`
- [x] `deleteMedia(requesterId, requesterIsAdmin, mediaId)`: proprietario o ADMIN (moderazione); prima la riga, poi l'oggetto (se lo storage fallisce la transazione torna indietro)
- [x] `MediaCleanupJob` (`@Scheduled` ogni 15 min): cancella gli upload `PENDING_UPLOAD` più vecchi di TTL + 1 h di margine, 100 per giro. Prima `deleteIfPending` (il DELETE blocca la riga: un confirm concorrente attende e poi fallisce sulla `@Version`), poi l'oggetto; se MinIO fallisce la transazione torna indietro e si riprova al giro dopo. Con più istanze il DELETE condizionale evita doppioni
- **Vincoli:**
  - object key generata dal server: `{yyyy}/{MM}/{uuid}.{ext da MIME}`. **Mai** il filename del client (path traversal / sovrascrittura) né l'`ownerId` (finirebbe negli URL)
  - TTL: upload 10 min, download 5 min
  - la quota conta i `READY` e i `PENDING_UPLOAD` il cui URL non è scaduto (i più vecchi non sono più caricabili e li cancella il job)
  - le risposte non espongono `ownerId` né l'object key (come `reporterId` in `HazardResponse`)
- Test: `MediaPolicyTest` (magic bytes, HTML dichiarato JPEG, file troncato, quota, motivi 415/413/422), `MediaServiceTest` (media di una segnalazione `REJECTED` nascosti agli altri, proprietario sempre ammesso, lock prima della quota)

**Checkpoint M3** (verificati il 28/09 dal vivo: app `local` + MinIO `pgsty` del compose, script con le API reali; `MediaUploadIT` resta facoltativo):
- [x] `upload-requests` → POST multipart con tutti i `formFields` e `file` sull'`uploadUrl` → 204 → confirm → `READY`; download dall'URL firmato: byte identici, `image/jpeg` + `Content-Disposition: attachment`
- [x] Upload di un file più grande del dichiarato → MinIO risponde 400 (`EntityTooLarge`); `Content-Type` diverso da quello firmato → 403
- [x] File di testo caricato come `image/jpeg` → confirm → `QUARANTINED` e oggetto sparito dal bucket
- [x] `application/x-msdownload` → 415 problem+json; 11 MB → 413
- [x] Media non allegato di un altro utente → 404 (anche `download-url`); media di una segnalazione `REJECTED` altrui → 404 (anche `/attached`), visibile al segnalatore e al PARK_MANAGER; HIKER su un sentiero → 403, su una segnalazione altrui → 404
- [x] Upload non confermato (retrodatato di 2 h) → al primo giro di `MediaCleanupJob` riga e oggetto spariti

---

### Milestone 4 — Service di dominio & controllo della concorrenza

#### M4.1 — Sentieri, POI, segnalazioni (query PostGIS)
Query native (servono il cast `::geography` e le funzioni PostGIS):
- [x] `TrailRepository`:
  - `List<Trail> findWithinDistance(double lon, double lat, double radiusMeters, int limit)` → predicato `ST_DWithin(path::geography, ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography, :radius)`, ordinato per `ST_Distance` (stesse espressioni geography)
  - `List<Trail> findIntersecting(double minLon, double minLat, double maxLon, double maxLat)` → `ST_Intersects(ST_MakeEnvelope(…, 4326), path)` (sentieri che attraversano il viewport della mappa; fino al 04/10 era `findContainedIn` con `ST_Contains`, ADR-0020)
- [x] `PoiRepository`: `List<Poi> findNearby(double lon, double lat, double radiusMeters, @Nullable String type, int limit)`
  - **Trappola:** `:type IS NULL` con un `null` non tipizzato fa fallire PostgreSQL ("could not determine data type of parameter"): serve `CAST(:type AS varchar)`. Vale per ogni parametro facoltativo nelle query native
- [x] `HazardReportRepository`: `List<HazardReport> findActiveNearTrail(UUID trailId, double bufferMeters)` → `ST_DWithin(h.location::geography, t.path::geography, :buffer)` con join su `trails`
- [x] `TrailRepository.findIntersecting(…, int limit)`: anche la bbox ha un `LIMIT` (max 200), altrimenti un viewport grande quanto il mondo restituisce tutto
- [x] (04/10, ADR-0020) `TrailSummary` ha il `path` semplificato con Douglas-Peucker a 0,00005° (circa 5 m): l'app disegna i sentieri della bbox senza un `GET /trails/{id}` per ognuno
- [x] View in `application/dto` (`TrailDetail`, `TrailSummary`, `PoiView`, `HazardView`): record con `GeoJsonLineString`/`GeoPointDto`, **mai** geometrie JTS (non serializzabili in JSON né cachabili). Si costruiscono con `from(entity, GeometryMapper)`: il service inietta il `GeometryMapper`. La dipendenza `application → shared/web/geo` è accettata (record puri, condivisi)
- [x] **Flush prima del DTO** in `create` (`saveAndFlush`), `update`, `changeStatus`: `length_m` (colonna generata) e `version` si aggiornano solo con l'SQL; senza flush la risposta ha `lengthM` null/vecchio e la version precedente (ETag sbagliato)
- [x] `TrailService` (il web è in M5.2):
  - `TrailDetail getTrail(UUID id)` → `@Cacheable("trails")`
  - `List<TrailSummary> findNearby(GeoQuery q)` → **non** in cache (parametri arbitrari, hit-rate ~0)
  - `List<TrailSummary> findInBoundingBox(BoundingBox box)`
  - `TrailDetail create(CreateTrailCommand c)` / `update(UUID, UpdateTrailCommand)` / `changeStatus(UUID, TrailStatus)` → `@PreAuthorize("hasRole('PARK_MANAGER')")` + `@CacheEvict("trails", key = "#id")`
  - `Trail getTrailOrThrow(UUID id)` (per le altre feature)
- [x] `HazardReportService`: `create(UUID reporterId, CreateHazardCommand)` (HIKER); `changeStatus(UUID, HazardStatus)` (PARK_MANAGER; `SUBMITTED` come destinazione → `BusinessRuleViolationException` 422, non `IllegalArgumentException` che diventerebbe 500); `findNearTrail` → 404 se il sentiero non esiste
- [x] `PoiService`: `findNearby(lon, lat, radius, @Nullable PoiType, limit)` con lo stesso tetto di raggio (50 km) e di `limit` (50) dei sentieri; `create` (PARK_MANAGER); `findByTrail` → 404 se il sentiero non esiste (come `HazardReportService.findNearTrail`)
- [x] `HazardReportService.getHazard`: `@PostAuthorize`, una segnalazione `REJECTED` la leggono solo `PARK_MANAGER` e chi l'ha fatta (`authentication.name` = `sub` del JWT)
- [x] Nel web: `BoundingBox` e `parseDouble` lanciano `IllegalArgumentException` (nessun handler → 500): `TrailController` fa il parsing di `?bbox=` e la traduce in `ResponseStatusException(400)`, che `ResponseEntityExceptionHandler` rende problem+json
- **Vincoli:** raggio massimo validato (es. ≤ 50 km) per evitare query-bomba; `limit` sempre presente; `BoundingBox` valida `min < max` e range lat/lon.

#### M4.2 — Prenotazioni rifugi (Pessimistic Lock)
**Problema:** due richieste concorrenti leggono "2 posti liberi" e prenotano entrambe → overbooking.
**Pattern:** serializzazione per rifugio con `SELECT … FOR UPDATE` sulla riga `refuges`, poi check di capacità **per notte**, nella stessa transazione.

- [x] `RefugeRepository`:
  - `@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from Refuge r where r.id = :id") Optional<Refuge> findByIdForUpdate(UUID id)`
- [x] `BookingRepository`:
  - `int maxNightlyOccupancy(UUID refugeId, LocalDate checkIn, LocalDate checkOut)` → nativa: per ogni notte di `generate_series(checkIn, checkOut - 1 day)` somma `guests` delle prenotazioni `CONFIRMED` che la coprono; ritorna il **massimo** (`COALESCE(…, 0)`)
  - `Optional<Booking> findByIdAndUserId(UUID id, UUID userId)`
  - `Optional<Booking> findByUserIdAndIdempotencyKey(UUID userId, UUID key)`
- [x] `RefugeBookingService` (web in M5.2: `RefugeController`, `BookingController`; il 428 per `Idempotency-Key`/`If-Match` mancanti è in `GlobalExceptionHandler.handleServletRequestBindingException`):
  - `@Transactional @PreAuthorize("hasRole('HIKER')") BookingResult book(UUID userId, UUID idempotencyKey, BookingCommand cmd)` → `BookingResult(BookingView booking, boolean created)`: `created = false` per un retry, il controller risponde 200 invece di 201:
    1. `findByIdForUpdate` sul rifugio (il lock viene **prima** della ricerca della chiave)
    2. `findByUserIdAndIdempotencyKey` → se esiste: stessa richiesta (`Booking.isSameRequest`: rifugio, date, ospiti) → restituisci quella prenotazione (retry del client, D12); body diverso → `IdempotencyKeyReusedException` (422)
    3. validazione delle date → `maxNightlyOccupancy + guests > capacity` ⇒ `CapacityExceededException` (409) → save
    - **Perché il lock prima della chiave:** due retry simultanei sullo stesso rifugio si mettono in fila sul lock, e il secondo trova la riga del primo (in READ COMMITTED ogni statement vede i commit precedenti). Cercando la chiave prima del lock, entrambi la vedrebbero assente e il secondo finirebbe sul vincolo `UNIQUE` (409 invece della prenotazione). Il vincolo resta come rete di sicurezza per la stessa chiave su rifugi diversi.
    - La chiave si cerca **prima** della validazione delle date: un retry arrivato quando il check-in è già passato restituisce comunque la prenotazione.
    - `CapacityExceededException` e `IdempotencyKeyReusedException` stanno in `shared/domain/exception`, con handler in `GlobalExceptionHandler` (lo shared non dipende dalle feature).
  - `void cancel(UUID userId, UUID bookingId)`: `findByIdAndUserId` (404 se non tuo, non 403: non riveli l'esistenza)
  - `AvailabilityView availability(UUID refugeId, LocalDate from, LocalDate to)` (read-only, senza lock; stesse regole di date della prenotazione: `from` non nel passato, `to > from`, max 14 notti → 422)
  - Letture e gestione: `getRefuge`, `findNearby(lon, lat, radius, limit)` (raggio ≤ 50 km e `limit` ≤ 50, come sentieri e POI), `getUserBookings`, `getBooking(userId, bookingId)` (404 se non tua, come `cancel`); `createRefuge` (PARK_MANAGER; il `managerId` è l'utente corrente, **non** arriva dal body) e `updateCapacity(actorId, actorIsAdmin, refugeId, capacity)` (PARK_MANAGER; dopo il lock, solo il gestore del rifugio o un ADMIN, altrimenti `AccessDeniedException` → 403; `findByIdForUpdate`: non si sovrappone a una prenotazione in corso)
  - Le view (`RefugeView`, `BookingView`, `AvailabilityView`) non contengono geometrie JTS: `RefugeView.from(refuge, GeometryMapper)` con `GeoPointDto`
  - ~~Limite noto~~ chiuso in M7.7: `updateCapacity` sotto gli ospiti già confermati → 409 `capacity-exceeded`
  - ~~Limite noto: `GET /bookings/my` senza paginazione~~ → paginato in M7.3 (`PagedModel`, sort in whitelist)
- **Perché per notte:** sommare tutte le prenotazioni che si sovrappongono al range sovrastima (due prenotazioni in notti diverse non si sommano).
- **Timeout:** `lock_timeout = 3s` (M0.3) → `PessimisticLockingFailureException` → 409 `resource-busy` + `Retry-After` (fatto in `GlobalExceptionHandler`).
- Limite noto: il lock sulla riga del rifugio serializza tutte le prenotazioni di quel rifugio. Va bene per decine di prenotazioni al secondo per rifugio; oltre quella soglia servirebbe una tabella `refuge_night_availability` con lock per notte.

#### M4.3 — Allerte SOS (Pessimistic Lock + evento asincrono)
- [x] `SosAlertRepository`: `findByIdForUpdate` e `findByIdAndHikerIdForUpdate` (`PESSIMISTIC_WRITE`; il secondo dà 404 per l'allerta di un altro); `findByStatusIn(…, Pageable)` con `@EntityGraph` sulla squadra; `lockHiker(long)` (`pg_advisory_xact_lock`); `findNearbyActive`; `findUnnotifiedCreatedBefore`, `markNotified`, `anonymizeClosedBefore` (native)
- [x] `RescueTeamRepository`: `findByIdForUpdate` (`PESSIMISTIC_WRITE`), `findAvailableNearby`
- [x] `SosService`:
  - `SosRaiseResult raiseSos(UUID hikerId, @Nullable UUID idempotencyKey, RaiseSosCommand)` (HIKER). **Un SOS non si rifiuta mai:** advisory lock per escursionista → stessa chiave (D12) o allerta già attiva → restituisce quella (`created = false`, 200); altrimenti crea e pubblica `SosAlertRaisedEvent(alertId, lon, lat, raisedAt)`. L'advisory lock serve perché non c'è una riga da bloccare: senza, due richieste simultanee creerebbero due allerte
  - `updateLiveLocation` / `cancelSos`: solo il proprietario (404 altrimenti), con lock
  - `dispatchTeam(operatorId, alertId, cmd)` → **ordine dei lock fisso: prima alert, poi team**; `resolveAlert` e `cancelSos` bloccano anche la squadra assegnata, sempre dopo l'allerta
  - `getAlert` con `@PostAuthorize` (operatori o proprietario: la `Location` del POST punta lì); `findActiveAlerts(Pageable)`; `findActiveNearby` (raggio ≤ 100 km)
  - squadre: `createTeam` (**solo ADMIN**), `getTeam`, `findAvailableTeamsNearby` (RESCUE_OPERATOR)
- [x] `AsyncConfig`: bean `sosNotificationExecutor` → core 4, max 8, queue 100, `CallerRunsPolicy` (un SOS non si scarta mai), prefix `sos-notify-`, attesa dei task allo shutdown, `TaskDecorator` che propaga l'MDC. Nessun executor dichiarato nelle feature
- [x] `SosNotificationListener`: `@Async("sosNotificationExecutor") @TransactionalEventListener(AFTER_COMMIT)` → `deliver(event)`: notifica (per ora mock; log **senza** coordinate né note mediche), poi `markNotified` (`UPDATE … WHERE id = ? AND notified_at IS NULL`)
- [x] **Recupero delle notifiche perse** (`SosNotificationRecovery`): `@Scheduled(fixedDelay = 30s)` rilancia `deliver` per le allerte attive con `notified_at IS NULL` e `created_at < now - 60s` (max 100 per giro). La notifica può partire due volte: chi la riceve deve tollerare i duplicati. Versione minima del *transactional outbox*
- [x] **Conservazione (GDPR)** (`SosDataRetentionJob`, 04:00 UTC): 90 giorni dopo `closed_at` la posizione si arrotonda a 0.01° (~1 km, `ST_SnapToGrid`) e le note mediche si cancellano; `anonymized_at` evita di ripassare sulle stesse righe
- [x] Rate limit: `POST /api/v1/sos` è escluso da `RateLimitFilter` (un SOS non riceve mai 429; l'abuso resta limitato perché il raise restituisce l'allerta attiva)
- **Vincoli:** `@Async` funziona solo se la chiamata passa dal proxy (non da `this.metodo()`); l'evento trasporta **dati**, non entità JPA né geometrie JTS.
- In alternativa al lock pessimistico basterebbe un `UPDATE … WHERE id=? AND status='ACTIVE'` condizionale. Il lock resta perché lo richiedi nelle specifiche e perché `dispatchTeam` tocca due righe.

#### M4.4 — Ticket di manutenzione (Optimistic Lock + cache)
- [x] `MaintenanceTicketRepository`: `findByTrailIdOrderByCreatedAtDesc(UUID trailId)`; `findByIdWithDetails` (fetch di trail e refuge); `searchTickets(status, priority, assignedTo, Pageable)` con `@EntityGraph` su trail e refuge (niente N+1 sui nomi)
- [x] `MaintenanceService` (`@PreAuthorize("hasRole('PARK_MANAGER')")` a livello di classe):
  - `TicketView createTicket(CreateMaintenanceTicketCommand)`: **esattamente uno** tra `trailId`, `refugeId`, `hazardReportId` (422 altrimenti); le entità arrivano da `TrailService.getTrailOrThrow`, `RefugeBookingService.getRefugeOrThrow`, `HazardReportService.getHazardOrThrow` (mai dai repository di altre feature); `saveAndFlush` prima del DTO
  - `List<TicketView> byTrail(UUID trailId)` → `@Cacheable("maintenance-by-trail")`, 404 se il sentiero non esiste
  - `getTicket`, `findTickets(TicketQuery, Pageable)`
  - `updateDetails` / `assignTicket` / `changeStatus(UUID id, long expectedVersion, …)`:
    1. carica il ticket
    2. **`if (expectedVersion != ticket.getVersion()) throw new OptimisticConflictException(...)`**: JPA usa la versione dell'entità caricata, non quella del client; senza questo check il lost update tra GET e PATCH del client non viene rilevato
    3. transizione sull'entità: `@Version` protegge la finestra load→commit
    4. `flush` prima del DTO (la version nuova va nell'`ETag`)
  - evict: `@CacheEvict(key = "#result.trailId()", condition = "#result.trailId() != null")` su ogni modifica (il sentiero di un ticket non cambia; i ticket di un solo rifugio non sono in cache)
  - l'assegnatario deve essere un utente attivo: `UserQueryService.isActiveUser` (identity), altrimenti 404
- `CacheConfig`:
  - [x] `RedisCacheManager` con **`.transactionAware()`**: put/evict avvengono dopo il commit (altrimenti un evict prima del commit + una lettura concorrente rimette in cache il dato vecchio)
  - [x] helper `CacheConfig.typed(...)` per un serializer JSON **tipizzato per cache**, senza *default typing* globale (rischio deserialization gadget); `disableCreateOnMissingCache()`: una cache non registrata fallisce invece di usare il serializer JDK
  - registrare le cache con `typed(...)` quando nascono i DTO, con un `RedisCacheManagerBuilderCustomizer` **nella feature** (`<feature>/infrastructure/cache`); prefisso chiavi `gs:cache:`:
    - [x] `trails` → `TrailDetail` (10 min), in `TrailCacheConfig`. Test: `TrailDetailCacheSerializationTest` (round-trip con lo stesso serializer)
    - [x] `maintenance-by-trail` → `List<TicketView>` (5 min), in `MaintenanceCacheConfig` (tipo costruito con `constructCollectionType`). Test di round-trip in `MaintenanceTicketTest`
- **Si cachano solo DTO record**, mai entità (proxy lazy + `@Version` non serializzabili in modo sensato).

**Checkpoint M4 (manuale, i test automatici arrivano in M6):**
- [x] `findNearby` con un punto a 100 m dal sentiero → lo trova con r=200, non con r=50 (`TrailSpatialQueryIT`)
- [x] Dopo un GET trail `gs:cache:trails::<id>` presente (TTL 600 s, valore JSON); dopo `PATCH /status` sparita; il GET successivo rilegge dal DB e rimette in cache (verificato dal vivo il 28/09)
- [x] Il log mostra `sos-notify-1` che gestisce l'evento dopo il commit, fuori dal thread della richiesta (`http-nio-*`), con lo **stesso `traceId`** (MDC propagato dal `TaskDecorator`); `notified_at` scritto 8 ms dopo `created_at` (verificato dal vivo il 28/09)
- [x] Due `POST /sos` con la stessa `Idempotency-Key` → una sola allerta, stessa risposta (`IdempotencyIT`)
- [x] Due `POST /bookings` con la stessa `Idempotency-Key` → 201 poi 200 con la stessa prenotazione, una sola riga; stessa chiave con body diverso → 422 (`IdempotencyIT`)
- [x] Allerta con `notified_at` NULL più vecchia di 60 s → il job la notifica una sola volta (`SosNotificationRecoveryIT`)

---

### Milestone 5 — API layer & gestione errori

#### M5.1 — `GlobalExceptionHandler` (RFC 7807)
**Pattern:** `@RestControllerAdvice` che estende `ResponseEntityExceptionHandler` (gestisce già 400/405/415 di Spring MVC in formato `ProblemDetail`).
- [x] `ProblemTypes`: costanti `URI` (`https://geosentinel.dev/problems/<slug>`), una per categoria
- [x] Ogni risposta: `type`, `title`, `status`, `detail`, `instance` (path) + properties `timestamp`, `traceId` (letto dall'MDC, popolato da Micrometer Tracing: vedi M0.1). Verificato: 404 dall'handler e 401 dal filtro hanno entrambi `traceId`

Stato: in `GlobalExceptionHandler` sono gestite tutte le righe, comprese le eccezioni dei media.

| Eccezione | Status | type slug |
|---|---|---|
| `MethodArgumentNotValidException` (override di `handleMethodArgumentNotValid`) | 400 | `validation-error` + property `errors: [{field, message}]` |
| `ConstraintViolationException` | 400 | `validation-error` |
| `ResourceNotFoundException` | 404 | `not-found` |
| `BusinessRuleViolationException` | 422 | `business-rule` |
| `CapacityExceededException`, `AlertAlreadyTakenException` | 409 | `capacity-exceeded` / `alert-already-taken` |
| `OptimisticConflictException`, `OptimisticLockingFailureException` | 409 | `concurrent-modification` |
| `PessimisticLockingFailureException` | 409 + `Retry-After` | `resource-busy` |
| `DataIntegrityViolationException` | 409 | `conflict` (**senza** nome del constraint nel detail) |
| `IdempotencyKeyReusedException` (stessa chiave, body diverso) | 422 | `idempotency-key-reused` |
| Header obbligatorio mancante (`If-Match`, `Idempotency-Key` sulle prenotazioni) (fatto: override di `handleServletRequestBindingException` per `MissingRequestHeaderException` sugli header in `PRECONDITION_HEADERS`; gli altri header mancanti restano 400) | 428 | `precondition-required` |
| `MediaRejectedException` (motivo `UNSUPPORTED_TYPE` / `TOO_LARGE` / `QUOTA_EXCEEDED`) | 415 / 413 / 422 | `media-unsupported-type` / `media-payload-too-large` / `media-quota-exceeded` |
| `StorageUnavailableException` | 503 + `Retry-After` | `storage-unavailable` (detail generico: il messaggio di MinIO può contenere host e bucket interni) |
| `AccessDeniedException` / `AuthorizationDeniedException` (da `@PreAuthorize`) | 403 | `forbidden` |
| `AuthenticationException` (credenziali o refresh token non validi, lanciati dai service) | 401 | `unauthorized` |
| `RateLimitExceededException` (limite per account sul login) | 429 + `Retry-After` | `rate-limit-exceeded` |
| `AccountManagedException` (`DELETE /users/me` del personale, M7.2) | 409 | `account-managed` |
| `AgeRequirementNotMetException` (registrazione sotto `geosentinel.registration.min-age`, M7.2.1) | 422 | `age-requirement-not-met` (detail generico, senza la soglia) |
| `InvalidResetCodeException` (codice sbagliato, scaduto, già usato o tentativi finiti, M7.2.2) | 422 | `invalid-reset-code` (un solo detail per tutti i motivi) |
| `Exception` (fallback) | 500 | `internal-error`, detail generico, log con `traceId`, **mai** stack trace |

**Trappola:** le eccezioni di `@PreAuthorize` sono lanciate dentro l'invocazione del controller e quindi **arrivano** all'advice. Senza un handler dedicato le prende il fallback `Exception` → 500 invece di 403.

#### M5.2 — Controller e DTO
**Pattern:** controller *thin*: `@Valid` → service → mapper → `ResponseEntity`. `201 Created` con header `Location`. Paginazione con `Pageable` + wrapper `PagedModel`, e **whitelist dei campi di `?sort=`** nel controller (400 altrimenti): il sort del client finisce nell'`ORDER BY` e un campo sconosciuto diventerebbe un 500 (vedi `MaintenanceController.checkSortable`). Niente enum salvati come stringa tra i campi ordinabili: si ordinerebbero in ordine alfabetico (per questo `priority` è escluso).

DTO comuni:
- [x] `GeoPointDto(@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat, @NotNull @DecimalMin("-180") @DecimalMax("180") Double lon)`
- [x] `GeoJsonLineString(String type /* "LineString" */, List<List<Double>> coordinates /* [lon, lat] */)`: validato nel costruttore (≥ 2 punti, coppie di 2, range lon/lat). `List<List<Double>>` e non `List<double[]>`: gli array non hanno `equals` per valore e si validano male con JSR-380.
- [x] `GeometryMapper` (MapStruct `@Mapper` con metodi `default`): `Point toPoint(GeoPointDto)`, `GeoPointDto toDto(Point)`, `LineString toLineString(GeoJsonLineString)`, `GeoJsonLineString toDto(LineString)`; usa il bean `GeometryFactory`
- [x] Validazione cross-field: **non** con `@AssertTrue` nel DTO per le prenotazioni. Le date (`checkIn` non nel passato, `checkOut > checkIn`, max 14 notti) le valida il service (422) **dopo** la ricerca della `Idempotency-Key`: un controllo nel DTO (es. `@FutureOrPresent`) farebbe fallire con 400 il retry di una prenotazione già creata, e userebbe il fuso della JVM invece del `Clock` UTC. `@AssertTrue` resta utile per regole cross-field che non dipendono dal tempo

Endpoint:

| Metodo & path | Ruolo | Note |
|---|---|---|
| `GET /api/v1/trails/{id}` | public | GeoJSON del path |
| `GET /api/v1/trails/nearby?lat&lon&radius&limit` | public | `radius ≤ 50000` |
| `GET /api/v1/trails?bbox=minLon,minLat,maxLon,maxLat` | public | |
| `POST /api/v1/trails` · `PUT /{id}` · `PATCH /{id}/status` | PARK_MANAGER | |
| `GET /api/v1/pois/{id}` · `/nearby` · `/trail/{trailId}` | public | `limit ≤ 50`; `/trail/{id}` → 404 se il sentiero non esiste |
| `POST /api/v1/hazards` | HIKER | |
| `GET /api/v1/hazards/{id}` | auth | `REJECTED` solo per PARK_MANAGER o per chi l'ha segnalata |
| `GET /api/v1/hazards/trail/{trailId}` | auth | buffer 100 m. **Non** sotto `/trails/**`: lì le GET sono `permitAll` e le segnalazioni diventerebbero pubbliche |
| `PATCH /api/v1/hazards/{id}/status` | PARK_MANAGER | |
| `GET /api/v1/refuges/{id}/availability?from&to` | auth | |
| `GET /api/v1/refuges/{id}` · `/nearby?lon&lat&radius&limit` | auth | `radius ≤ 50000`, `limit ≤ 50` |
| `POST /api/v1/refuges` | PARK_MANAGER | 201 + `Location`; il gestore (`managerId`) è chi lo crea, non arriva dal body |
| `POST /api/v1/bookings` (`refugeId` nel body) | HIKER | 201 + `Location`; 409 se pieno; **`Idempotency-Key` (UUID) obbligatorio** → 428 se manca, 400 se non è un UUID; retry con la stessa chiave → 200 con la prenotazione esistente (`BookingResult.created`) |
| `GET /api/v1/bookings/my` (`PagedModel`, M7.3) · `GET /api/v1/bookings/{id}` | owner | 404 per una prenotazione altrui; sort solo `checkIn`/`checkOut`/`createdAt` |
| `DELETE /api/v1/bookings/{id}` | owner | 204 |
| `GET /api/v1/users/me` · `PATCH /api/v1/users/me` (M7.2) | authenticated | profilo (`UserProfileResponse`); il PATCH cambia `displayName` e restituisce il profilo |
| `POST /api/v1/users/me/password` (M7.2) | authenticated | 200 + nuovi token, tutte le altre sessioni revocate; password attuale sbagliata → 422 |
| `DELETE /api/v1/users/me` (M7.2) | HIKER | 204, anonimizzazione; personale → 409 `account-managed` |
| `POST /api/v1/auth/register` (M7.2.1) | public | `birthDate` obbligatoria; sotto i 14 anni → 422 `age-requirement-not-met` |
| `POST /api/v1/auth/password-reset/request` (M7.2.2) | public | sempre 204, anche per un'email sconosciuta; 3 richieste all'ora per email → 429 |
| `POST /api/v1/auth/password-reset/confirm` (M7.2.2) | public | 204, tutte le sessioni chiuse; codice non valido → 422 `invalid-reset-code` |
| `PATCH /api/v1/refuges/{id}/capacity` | PARK_MANAGER **gestore del rifugio**, o ADMIN | capacità; un altro PARK_MANAGER → 403 |
| `POST /api/v1/maintenance-tickets` (`trailId` / `refugeId` / `hazardReportId` nel body, esattamente uno) | PARK_MANAGER | 201 + `Location` + `ETag` |
| `GET /api/v1/maintenance-tickets/{id}` · `?status&priority&assignedTo` (paginato, `PagedModel`) | PARK_MANAGER | `ETag` sul singolo |
| `GET /api/v1/maintenance-tickets/trail/{trailId}` | PARK_MANAGER | cached; **non** sotto `/trails/**` (GET `permitAll`: un anonimo avrebbe 403 invece di 401) |
| `PUT /api/v1/maintenance-tickets/{id}` · `PATCH /{id}/assign` · `PATCH /{id}/status` | PARK_MANAGER | **`If-Match: "<version>"` obbligatorio** → 428 se manca, 400 se non è un numero, 409 se vecchia; risposta con `ETag`, `version` non nel body |
| `POST /api/v1/sos` | HIKER | 201 + `Location`; `Idempotency-Key` facoltativo (mai rifiutare un SOS); retry o allerta già attiva → 200 con l'allerta esistente; nessun rate limit |
| `PUT /api/v1/sos/{id}/location` | owner | 404 per un'allerta altrui |
| `DELETE /api/v1/sos/{id}` | owner | 204 |
| `GET /api/v1/sos/active` | owner | 404 se nessuna allerta attiva |
| `GET /api/v1/sos/{id}` | owner / RESCUE_OPERATOR | |
| `GET /api/v1/sos` (attive, `PagedModel`) · `/nearby?lon&lat&radius&limit` | RESCUE_OPERATOR | sort solo `createdAt`/`updatedAt`; `radius ≤ 100000` |
| `PATCH /api/v1/sos/{id}/dispatch` `{teamId}` | RESCUE_OPERATOR | 409 `alert-already-taken` se una squadra è già stata inviata |
| `PATCH /api/v1/sos/{id}/resolve` | RESCUE_OPERATOR | libera la squadra |
| `POST /api/v1/rescue-teams` | ADMIN | 201 + `Location` |
| `GET /api/v1/rescue-teams/{id}` · `/available-nearby?lon&lat&radius&limit` | RESCUE_OPERATOR | |
| `POST /api/v1/media/upload-requests` | auth (target `TRAIL` solo PARK_MANAGER; `HAZARD_REPORT` solo il segnalatore) | 201 + `Location`; risponde `uploadUrl` + `formFields` (POST policy); 415 / 413 / 422 quota |
| `POST /api/v1/media/{id}/confirm` | owner | `READY` o `QUARANTINED` nel body; 404 se non tuo |
| `GET /api/v1/media/{id}` · `/{id}/download-url` | owner, o chiunque se `READY` e allegato a un'entità visibile | 404 altrimenti; download con `Content-Disposition: attachment` |
| `GET /api/v1/media/attached?entityType&entityId` | auth | solo `READY`; 404 per una segnalazione non visibile |
| `GET /api/v1/media/my` | auth | `PagedModel`, sort solo `createdAt`/`sizeBytes` |
| `DELETE /api/v1/media/{id}` | owner o ADMIN | 204 |
| `POST /api/v1/devices` `{token, platform}` (M7.4) | authenticated | 204; upsert: un token già registrato passa all'utente corrente |
| `DELETE /api/v1/devices/{token}` (M7.4) | authenticated | 204 idempotente; il token di un altro utente non si tocca |

Mapping: i service restituiscono view (`from(entity, GeometryMapper)`, vedi M4.1); il web ha i suoi record di request e **response** e un mapper MapStruct per feature (`TrailMapper`, `RefugeMapper`, `MaintenanceMapper`, `SosMapper`, `MediaMapper`, `uses = GeometryMapper.class`) per request → command e view → response. La response separata decide cosa esce dall'API: `HazardResponse` non espone `reporterId` (dato personale), `TrailDetailResponse` non espone `version` (va nell'`ETag` quando servirà), `TicketResponse` nemmeno (è nell'`ETag`).

Validazione dei parametri: niente `@Validated` sui controller. Spring MVC valida già da solo `@RequestParam`/`@PathVariable` annotati (`HandlerMethodValidationException`, gestita in `GlobalExceptionHandler`); con `@Validated` si aggiungerebbe un proxy AOP che valida una seconda volta.

#### M5.3 — OpenAPI
- [x] `OpenApiConfig`: `@SecurityScheme(name = "bearerAuth", type = HTTP, scheme = "bearer", bearerFormat = "JWT")`, info del progetto
- [x] `@Tag` per controller (già presenti); risposte di errore con schema `ProblemDetail` (`application/problem+json`) aggiunte da un solo `OpenApiCustomizer` in `OpenApiConfig`, invece di `@ApiResponse` ripetute: 400, 429 e 500 ovunque; 401/403 sulle operazioni con `bearerAuth`; 404 sui path con `{…}`; 409 sulle scritture autenticate. Le risposte già dichiarate (`AuthController`) restano. Ogni rotta protetta deve dichiarare `security = @SecurityRequirement(name = "bearerAuth")`: senza, Swagger non manda il token (mancava sulle 3 GET di `RefugeController`)
- [x] Docs disabilitate fuori da `local` (`springdoc.api-docs.enabled: false` nel profilo base)
- [x] Da M7.1 il documento è il contratto del client: ogni `@Operation` ha un `operationId` (verbo + risorsa), i campi mai null dei `*Response` hanno `@Schema(requiredMode = REQUIRED)`, ogni `Pageable` ha `@ParameterObject`. Lo controlla `OpenApiContractIT` contro `docs/openapi.json`

**Checkpoint M5:**
- [x] Swagger UI su `/swagger-ui.html`: "Authorize" con il token di `hiker@` → `GET /refuges/{id}` parte con `Authorization: Bearer` e risponde 200 (verificato nel browser il 28/09; senza token 401)
- [x] Body malformato → 400 con `errors[]`; lat=200 → 400 (`ProblemDetailContractIT`)
- [x] PATCH ticket senza `If-Match` → 428 (`MaintenanceControllerTest`); con versione vecchia → 409 (`MaintenanceOptimisticLockIT`)
- [x] `POST /bookings` senza `Idempotency-Key` → 428; non UUID → 400 (`BookingControllerTest`)
- [x] **Nessuna** risposta di errore con `Content-Type` diverso da `application/problem+json` (`ProblemDetailContractIT`: 400/401/403/404/409/428/429, tutti con `traceId`; dai filtri arriva anche `;charset=UTF-8`)

---

### Milestone 6 — QA & integration testing

#### M6.1 — `AbstractIntegrationTest`
**Pattern:** *singleton containers*: container `static`, avviati una volta per JVM, condivisi da tutte le classi IT. Così il context cache di Spring resta valido e la suite resta veloce.
- [x] `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("test")`
- [x] `PostgreSQLContainer` (Testcontainers 2: `org.testcontainers.postgresql`, non generico) con `postgis/postgis:16-3.4-alpine` + `asCompatibleSubstituteFor("postgres")`; proprietà via `@DynamicPropertySource`
- [x] Redis: `GenericContainer("redis:7.2-alpine").withExposedPorts(6379)`
- [x] Avvio nel blocco `static` con `Startables.deepStart` (in parallelo; **non** `@Container`, che li fermerebbe a fine classe)
- [x] Pulizia: **`@BeforeEach`** (non `@AfterEach`: si parte puliti anche se il test precedente è morto) con `TRUNCATE … CASCADE` di tutte le tabelle + `FLUSHALL` Redis (azzera anche i bucket del rate limit). Niente `@Transactional` sui test
- [x] Helper: `obtainToken(Role)`, `createUser(Role)`, `call(...)` (HTTP vero sulla porta, serve alla concorrenza), `runConcurrently(n, task)` (partenza con `CountDownLatch`), fixture SQL `insertTrail/insertRefuge/insertTeam`, `isProblemJson`
- [x] `application-test.properties`: segreto JWT e chiavi MinIO finte, password Redis vuota (il `.env` locale non deve finire nei test). Rate limit: le policy sono costanti nel codice, quindi niente override: `FLUSHALL` prima di ogni test e ogni test resta sotto le 5 chiamate a `/auth`. Access token scaduto: firmato a mano in `AuthFlowIT`, niente `accessTtl` corto (un contesto in più)

#### M6.2 — Suite (ognuna = un file)
| Test | Cosa dimostra | Criterio |
|---|---|---|
| `MigrationIT` | Flyway V1–V7 + `ddl-auto=validate` su vero PostGIS | context carica |
| `TrailSpatialQueryIT` | `ST_DWithin` in **metri**, `ST_Intersects` bbox, percorso semplificato | punto a ~100 m trovato con r=200, non con r=50; bbox che taglia il sentiero lo trova, una accanto no; **regressione lon/lat**: con coordinate invertite il test fallisce |
| `RefugeBookingConcurrencyIT` | nessun overbooking | capacity 10, **50 thread** × 1 posto stesse date, ognuno con la **sua** `Idempotency-Key` (con la stessa chiave e lo stesso utente sarebbe un retry, non una nuova prenotazione), `CountDownLatch` di partenza → **esattamente 10 successi, 40 × 409**, `maxNightlyOccupancy == 10` |
| `SosAssignmentConcurrencyIT` | invio esclusivo della squadra | 10 operatori concorrenti su un alert, ognuno con una squadra diversa → **1 successo, 9 × 409**; dispatch concorrente dello stesso team su 2 alert → 1 successo |
| `MaintenanceOptimisticLockIT` | lost update impedito | due update con la stessa `expectedVersion` → il secondo 409; versione finale = +1 |
| `AuthFlowIT` | ciclo token completo | register/login/refresh/reuse-detection/logout-blacklist/sessione assoluta scaduta/password > 72 byte → 400 (scenari del checkpoint M2) |
| `IdempotencyIT` | nessun duplicato sui retry | 10 thread con la stessa `Idempotency-Key` su `POST /sos` e su una prenotazione → **una sola riga** creata, **un** 201 e nove 200 con lo stesso id; stessa chiave con body diverso → 422 sulle prenotazioni, 200 con l'allerta esistente sugli SOS (mai rifiutati); dieci `POST /sos` **senza** chiave dallo stesso utente → una sola allerta (advisory lock) |
| `SosNotificationRecoveryIT` | nessun SOS senza notifica | allerta con `notified_at` NULL più vecchia di 60 s → il job la notifica; lanciato due volte → una sola notifica effettiva |
| `RateLimitIT` | 429 + `Retry-After` | N+1-esima richiesta bloccata |
| `ProblemDetailContractIT` | contratto errori | 400/401/403/404/409/429 → `application/problem+json` con `type/title/status/instance` |
| Unit: `AuthServiceLoginLimitTest`, `AuthServiceLogoutTest`, `RefugeBookingServiceTest`, `MediaServiceTest`, `SosAlertTest` | logica pura con Mockito | nessun container. `AuthServiceLoginLimitTest` verifica che con email inesistente `passwordEncoder.matches` venga **comunque** chiamato (tempo costante); `MediaServiceTest` copre i magic bytes |

Pattern del test di concorrenza (da scrivere tu): `ExecutorService` a N thread, `CountDownLatch ready/start`, ogni task chiama il **service** (non il repository) e registra successo/eccezione in collezioni thread-safe; `invokeAll` + asserzioni sui conteggi. Esegui ogni test di concorrenza **almeno 5 volte** di fila (`@RepeatedTest(5)`): se passa solo a volte, hai una race.

Opzionali, da aggiungere quando servono (`MediaUploadIT` fatto in M7.5): un test ArchUnit che fa rispettare le regole di dipendenza del §1 (utile se il team cresce); JaCoCo.

**Checkpoint M6 (Definition of Done del backend):**
- [x] `./mvnw verify` verde da zero con Docker attivo (41 unit + 63 IT, ~45 s; due esecuzioni consecutive verdi)
- [x] Test di concorrenza verdi 5/5 (`@RepeatedTest(5)`)
- [x] Nessun test usa H2 o database in memoria

---

### Milestone 7 — Backend per l'app mobile Kotlin Multiplatform (Android + iOS)

**Contesto:** non ci sarà un frontend web: l'unico client è un'app **Kotlin Multiplatform** (Android e iOS, rete con Ktor, client generato dal documento OpenAPI). Conseguenze già valide senza lavoro: **niente CORS** (lo usano solo i browser); refresh token **nel body JSON** (corretto per il mobile: l'app lo salva cifrato, Android Keystore / iOS Keychain).

Stato verificato il 28/09 su `/v3/api-docs` e sul codice (motiva i task sotto):
- 15 `operationId` con suffisso numerico (`create_4`, `getById_5`, …): nel client generato diventano `create4()`, e cambiano quando si aggiunge un controller
- nessuno schema di risposta ha `required`: in Kotlin ogni proprietà diventerebbe nullable
- nessuna rotta `/users`: l'app non può leggere il profilo né cancellare l'account (obbligatorio per App Store e Google Play)
- `MediaPolicy` accetta solo JPEG/PNG/WebP: la fotocamera di iPhone salva in HEIC
- rate limit di 5 req/min **per IP** su `/auth/**`: sulle reti mobili molti utenti escono con lo stesso IP (CGNAT degli operatori)

Ordine consigliato: M7.1 → M7.2 + M7.2.1 (stessa `V8`) → M7.3 (servono al client per partire) → M7.4 → M7.5 → M7.6 → M7.7.

#### M7.1 — Contratto OpenAPI stabile per la generazione del client
**Obiettivo:** il client Kotlin si genera da `/v3/api-docs` (openapi-generator, `kotlin` + Ktor, o equivalente KMP) senza ritocchi a mano.
- [x] `operationId` esplicito e unico su ogni `@Operation` (es. `createBooking`, `getTrail`, `raiseSos`): verbo + risorsa, stabile nel tempo. Checkpoint automatico: un test che legge `/v3/api-docs` e fallisce se un `operationId` finisce con `_<numero>`
- [x] Campi delle **risposte** marcati obbligatori dove non sono mai null (`@Schema(requiredMode = REQUIRED)` sulle componenti dei record `*Response`, oppure nel `OpenApiCustomizer` a partire dai tipi primitivi e dagli id). Le request hanno già `@NotNull`/`@NotBlank`, che springdoc traduce in `required`
- [x] Enum pubblicati come enum nello schema (già così) e date come `format: date` / `date-time` (ISO-8601, `Instant` in UTC: in Kotlin `kotlinx.datetime.Instant` / `LocalDate`)
- [x] Documento OpenAPI versionato con uno **snapshot test**, non con `springdoc-openapi-maven-plugin` (il plugin vuole l'app accesa durante il build, quindi Postgres/Redis fuori da Testcontainers):
  - `shared/OpenApiContractIT` (estende `AbstractIntegrationTest`) legge `/v3/api-docs`, lo normalizza (JSON indentato, chiavi ordinate, `servers` rimosso: contiene la porta casuale) e lo confronta con `docs/openapi.json`. Diverso → il test fallisce con il diff
  - `-Dopenapi.update=true` riscrive il file invece di confrontarlo (si lancia a mano dopo una modifica voluta alle API, poi si committa)
  - in `application-test.properties`: `springdoc.api-docs.enabled=true` (nel profilo base è spento: senza, il test riceve 404)
  - nello stesso test: nessun `operationId` che finisce con `_<numero>`
  - il progetto KMP genera il client da questo file, non da un server acceso
- [x] Catalogo degli errori per il client: gli slug di `ProblemTypes` sono stabili e documentati (tabella in M5.1): il client li mappa su una `sealed class` di errori, mai sul testo di `detail`

**Checkpoint:** generazione del client Kotlin dal file committato senza warning; nessun metodo con suffisso numerico; `TokenResponse.accessToken` non nullable nel codice generato.

**Fatto (29/09, scritto da Claude su richiesta), tutte le voci sopra:**
- 53 `operationId` espliciti; `@Schema(requiredMode = REQUIRED)` sulle componenti mai null dei record `*Response` (nullable solo quelle con colonna nullable nel DDL); nel customizer `required` su `ProblemDetail`, `FieldError` (item di `errors`, con `title` per il nome della classe generata), `PagedModel*` e `PageMetadata`; slug di `ProblemTypes` nella descrizione di `ProblemDetail.type` (letti per reflection, non un enum).
- `shared/OpenApiContractIT`: snapshot `docs/openapi.json` (`-Dopenapi.update=true` per riscriverlo), nessun `operationId` mancante/duplicato/`_<n>`, nessun oggetto in query.
- **Bug trovato:** `Pageable` senza `@ParameterObject` finiva nel contratto come un unico parametro `pageable` (oggetto): il client generato avrebbe mandato `?pageable=...` e Spring, che legge `page`/`size`/`sort`, avrebbe ignorato la paginazione. Corretto su `searchTickets`, `findMyMedia`, `findActiveSosAlerts`; ogni nuovo endpoint paginato usa `@ParameterObject @PageableDefault(...) Pageable`.
- Trappola: il pretty printer di Jackson usa `System.lineSeparator()` (`\r\n` su Windows): lo snapshot si normalizza a `\n` (anche `.gitattributes`), altrimenti rosso su Windows e verde in CI.
- Verificato: client generato con `openapi-generator` 7.x (`kotlin`, `--library multiplatform`, `dateLibrary=kotlinx-datetime`): 53 metodi con i nomi del contratto, nessun suffisso numerico, `TokenResponse.accessToken: String` non nullable, date `Instant`/`LocalDate`. Unico warning: "OpenAPI 3.1 support is still in beta" del generatore. `./mvnw clean verify` verde: 41 unit + 66 IT.
- Per l'app: gli enum generati sono rigidi (un valore nuovo lato server rompe la deserializzazione). Nel client `Json { ignoreUnknownKeys = true }`, e aggiungere valori agli enum delle risposte solo insieme a un aggiornamento dell'app.

#### M7.2 — Profilo utente e cancellazione dell'account
**Obiettivo:** l'app mostra e modifica il profilo; l'utente può cancellare l'account dall'app (**obbligatorio**: App Store Review Guideline 5.1.1(v); Google Play chiede anche un link web per la richiesta di cancellazione).
- [x] `identity/web/UserController` (`/api/v1/users/me`):
  - `GET` → `UserProfileResponse(id, email, displayName, role, createdAt)` (il JWT contiene solo `sub` e `roles`, niente dati personali)
  - `PATCH` → `UpdateProfileRequest(@NotBlank @Size(2..100) displayName)`
  - `POST /password` → `ChangePasswordRequest(currentPassword, @Size(min=12) @MaxUtf8Bytes(72) newPassword)`: verifica la password attuale, revoca tutte le famiglie di refresh token dell'utente
  - `DELETE` → 204: cancellazione dell'account (sotto)
- [x] `identity/application/UserAccountService`: `deleteAccount(UUID userId, Jwt accessToken)` in una transazione:
  - revoca refresh token (tutte le famiglie) + blacklist del `jti` corrente
  - anonimizzazione, non `DELETE` fisico: le FK (segnalazioni, prenotazioni, SOS, ticket) restano valide. `email` → `deleted-<uuid>@invalid`, `display_name` → `Utente cancellato`, `password_hash` → hash non verificabile, `enabled = false`, `deleted_at = now`
  - prenotazioni future `CONFIRMED` → `CANCELLED`; allerta SOS attiva → `CANCELLED`
  - media dell'utente: cancellati (riga + oggetto) tramite `MediaService`, non via repository (regola del §1). Un HIKER può caricare foto solo sulle sue segnalazioni, quindi sono dati suoi e non contenuto condiviso
  - segnalazioni di pericolo: restano (servono agli altri escursionisti), `reporter_id` punta all'utente anonimizzato
  - le altre feature espongono un metodo nel loro service (`RefugeBookingService.cancelAllFutureOf(userId)`, `SosService.cancelActiveOf(userId)`, `MediaService.deleteAllOf(userId)`): `identity` non usa i loro repository
- [x] **Self-service solo per `HIKER`.** `DELETE /users/me` con ruolo `PARK_MANAGER`, `RESCUE_OPERATOR` o `ADMIN` → 409 `account-managed` ("contact an administrator"). Motivo (pratica comune degli account aziendali, es. Google Workspace, Microsoft Entra): gli account del personale li crea e li chiude l'organizzazione, perché hanno in carico rifugi (`manager_id`), ticket assegnati, missioni e foto dei sentieri. L'obbligo degli store riguarda gli account che l'utente crea dall'app, cioè solo gli HIKER (la registrazione crea solo HIKER). La chiusura di un account del personale (riassegnazione di rifugi e ticket, poi anonimizzazione) è una funzione ADMIN, fuori da M7
  - nuovo slug `ACCOUNT_MANAGED` in `ProblemTypes` + eccezione in `shared/domain/exception` + handler 409
- [x] `V8__users_account.sql`: `users.deleted_at timestamptz NULL`, `users.age_confirmed_at timestamptz NULL` (vedi M7.2.1); `User.markDeleted(Instant now)` nel dominio
- [x] Login e refresh rifiutano un utente con `deleted_at` (già coperto da `enabled = false`: verificarlo in un test)

**Checkpoint:** `UserAccountIT`: dopo `DELETE /users/me` → login 401, refresh 401, access token corrente 401; prenotazione futura `CANCELLED`; email originale riutilizzabile per una nuova registrazione; `DELETE /users/me` con un token `PARK_MANAGER` → 409 `account-managed` e l'account resta attivo.

#### M7.2.1 — Età minima in registrazione (GDPR art. 8)
**Norma:** per i servizi della società dell'informazione il GDPR (art. 8) fissa a 16 anni l'età per acconsentire da soli, e ogni Stato può abbassarla fino a 13. In Italia è **14 anni** (art. 2-quinquies del Codice privacy, D.Lgs. 101/2018). Sotto quella soglia servirebbe il consenso verificato di un genitore: GeoSentinel non lo gestisce, quindi sotto i 14 anni non si registra.

Scelte (prassi delle linee guida ICO, *Age Appropriate Design Code*, e dell'EDPB):
- [x] **Age gate neutro:** `RegisterRequest.birthDate` (`@NotNull LocalDate`, ISO-8601). Mai una casella "ho almeno 14 anni": suggerisce la risposta. L'app mostra un campo data vuoto, senza indicare la soglia
- [x] **Minimizzazione (art. 5.1.c):** la data di nascita serve solo al controllo e **non si salva**. Si salva `users.age_confirmed_at` (quando il controllo è passato), che basta a dimostrare di averlo fatto (accountability, art. 5.2). Gli utenti del seed e quelli creati da un ADMIN restano con `NULL`
- [x] Controllo in `AuthService.register`, **prima** di ogni scrittura (del minore non resta nessun dato): `Period.between(birthDate, LocalDate.now(clock)).getYears() >= minAge`. La soglia con il `Clock`, non con un'annotazione sul DTO (fuso della JVM, come `@FutureOrPresent` sulle prenotazioni)
  - data nel futuro o mancante → 400 (`@NotNull @Past` sul DTO, `errors[]`): `@Past` usa il fuso della JVM, ma sbaglia solo su oggi/domani, che la soglia rifiuta comunque. Niente limite inferiore (1900): una data vecchia passa solo la soglia, come qualsiasi data adulta
  - età sotto la soglia → 422 `age-requirement-not-met`, `detail` generico ("registration is not available for this age") senza la soglia
- [x] Soglia configurabile: `geosentinel.registration.min-age=14`. Cambia da Stato a Stato (13–16), e un'apertura in altri paesi UE non richiede codice
- [x] Nuovo slug `AGE_REQUIREMENT_NOT_MET` in `ProblemTypes` + eccezione in `shared/domain/exception` + handler 422; catalogo degli errori di M7.1 aggiornato
- [x] Dopo un rifiuto l'app non permette di ripetere subito la registrazione con un'altra data: lo ricorda in locale. È compito dell'app, il backend non conserva nulla del tentativo

Fuori da M7: il consenso verificato dei genitori per i 13enni; le API di età degli store (iOS *Declared Age Range*, Google Play *Age Signals*). Si valutano lato app se le leggi USA sull'età negli store diventano rilevanti.

**Checkpoint:** `RegistrationAgeIT`: 14 anni compiuti oggi → 201 e `age_confirmed_at` valorizzato; un giorno prima del compleanno → 422 `age-requirement-not-met` e nessuna riga in `users`; data futura → 400; `birthDate` mancante → 400; nessuna colonna con la data di nascita nello schema.

**Fatto (29/09): M7.2 e M7.2.1 scritti dall'utente, revisionati e corretti da Claude su richiesta.**
- Non compilava: classi inesistenti (`booking.application`, `JwtTokenBlacklistService`, `RefreshTokenService`, `getFirstName`), `req`/`minAge`/`User.registerHiker` in `register`, `SosService.cancelActiveOf` con due parametri, `MediaService.deleteAllOf` con `markDeleted()`/`getBucketName()`, handler vuoti.
- Bug corretti:
  - `register` salvava l'utente due volte e prima del controllo sull'email;
  - `User.markDeleted`, `cancelAllFutureOf` e `cancelActiveOf` vuoti o senza effetto: niente anonimizzazione, prenotazioni e SOS non cancellati;
  - `UUID id(UUID id)` in `SosAlertRepository`: query derivata non valida, l'app non sarebbe partita;
  - password attuale sbagliata → `IllegalArgumentException` → 500: ora 422 (non 401, l'app crederebbe scaduto il token);
  - `@Size(max = 72)` contava caratteri: `@MaxUtf8Bytes(72)` su entrambe le password (anche la attuale, oltre 72 byte BCrypt lancia);
  - hash inutilizzabile di 73 byte (`UUID:UUID`) → BCrypt lancia → 500 sulla cancellazione: ora un solo UUID;
  - `birthDate` assente da `RegisterRequest`/`RegisterCommand`.
- Allineamenti: eccezioni che estendono `DomainException`, slug in `ProblemTypes`, DTO in `identity/web/dto/UserDtos` (non nel controller), `role` come enum, niente `@Validated` né import `*`, `@ResponseStatus(NO_CONTENT)` sul DELETE.
- Scelte: `changePassword` sta in `AuthService` e restituisce nuovi token (200 `TokenResponse`: tutte le sessioni revocate, l'app resta connessa, gli altri dispositivi no); `PATCH /users/me` restituisce il profilo (200); `deleteAccount` esegue prima tutto ciò che il rollback annulla, poi la blacklist del `jti` (Redis giù → rollback), per ultimi gli oggetti MinIO; nome anonimizzato `Deleted user`, email `deleted-<id>@invalid`.
- Test: `UserAccountIT` (4), `RegistrationAgeIT` (4); aggiornati `AuthFlowIT`, `MaxUtf8BytesTest`, i due unit test di `AuthService`, `MigrationIT` (V8). `docs/openapi.json` rigenerato. `./mvnw clean verify` verde: 41 unit + 74 IT.
- Limite noto: se MinIO fallisce a metà di `deleteAllOf`, gli oggetti già cancellati non tornano; la riprova della cancellazione è idempotente.

#### M7.2.2 — Reset della password con codice via email
> Aggiunta il 17/10, dopo M7: l'app non aveva modo di rientrare con una password dimenticata. Scritta dall'utente, revisionata e corretta da Claude su richiesta.

**Obiettivo:** chi ha dimenticato la password ne imposta una nuova dall'app con un codice ricevuto per email, senza rivelare quali email sono registrate.

**Perché un codice e non un link:** l'app è l'unico client (D13) e non esiste una pagina web su cui atterrare; un link richiederebbe deep link verificati (App Links / Universal Links). Il codice si copia nella stessa schermata della nuova password.

- [x] **Codice:** 16 caratteri dall'alfabeto di Crockford (senza `I L O U`), ~80 bit, mostrato come `XXXXXXXX-XXXXXXXX`. `PasswordResetCode.normalize` accetta minuscole, trattini, spazi e le lettere ambigue (`O → 0`, `I/L → 1`). Valido 15 minuti (`geosentinel.password-reset.ttl`), una sola volta, consumato dopo 5 errori (`max-attempts`)
- [x] `V13__password_reset_tokens.sql`: `id`, `user_id` FK (indice `(user_id, created_at DESC)`), `code_hash` (BCrypt con `{bcrypt}`, il codice in chiaro non si salva), `expires_at` (indice per la pulizia), `consumed_at`, `failed_attempts smallint CHECK >= 0`, `created_at`
- [x] **`POST /api/v1/auth/password-reset/request`** (`requestPasswordReset`) → sempre 204:
  - nel thread della richiesta solo il limite e l'evento `PasswordResetRequested(normalizedEmail)`: nessuna lettura del DB né BCrypt, così il tempo di risposta non dice se l'account esiste (`PasswordResetServiceTest`)
  - limite **per email** di 3 richieste all'ora (`RedisPasswordResetLimiter`, `rl:pwreset::<sha256>`, fail-closed), uguale per le email inesistenti: anche il 429 non rivela nulla
  - `PasswordResetMailListener` (`@Async("passwordResetMailExecutor")` + `@EventListener`) chiama `issueCode`, che in una transazione blocca l'utente, consuma i codici aperti, salva il nuovo e lo restituisce **dopo il commit**; poi spedisce. Un'email non parte mai per un codice non salvato
  - executor dedicato a un thread (FIFO, `MdcTaskDecorator` per il `traceId`), separato da `sosNotificationExecutor`: un SMTP lento non ritarda gli SOS
- [x] **`POST /api/v1/auth/password-reset/confirm`** (`confirmPasswordReset`) → 204 o 422 `invalid-reset-code`:
  - lock sull'utente (`findByIdForUpdate`), poi il codice aperto più recente: stesso ordine di `changePassword` (ADR-0021)
  - BCrypt **sempre**: hash fittizio se non c'è un codice aperto, segnaposto fisso al posto di un codice malformato (mai l'input grezzo: oltre 72 byte BCrypt lancia → 500)
  - un errore conta anche se la risposta è 422: `@Transactional(noRollbackFor = InvalidResetCodeException.class)`
  - codice giusto: consumato con tutti gli altri aperti, nuova password (`@Size(min = 12)`, `@MaxUtf8Bytes(72)`, come M7.2), refresh token revocati e `revokeAllIssuedUntil(userId, now)` (M7.7). Nessuna sessione nuova: l'app manda al login
  - limite per account sul login azzerato (`LoginAttemptLimiter.reset`): chi era bloccato dai tentativi sbagliati entra subito con la password nuova
- [x] **Email:** porta `PasswordResetMailer`, `SmtpPasswordResetMailer` (MIME UTF-8, testo semplice) se `geosentinel.mail.enabled=true`, altrimenti `LoggingPasswordResetMailer` (log senza email né codice). Configurazione `MAIL_*` nel `.env` → `spring.mail.*`, timeout SMTP di 5/10 s
  - `management.health.mail.enabled=false`: un SMTP giù non deve portare `/actuator/health` a 503 (proxy e compose darebbero morta l'app, SOS compresi)
  - sviluppo: Mailpit nel compose (`axllent/mailpit`, SMTP 1025, caselle su `http://localhost:8025`), acceso dal profilo `local`
- [x] Cancellazione dell'account: `UserAccountService.deleteAccount` cancella anche i codici (`PasswordResetTokenRepository.deleteAllOf`, regola di M7.2)
- [x] `PasswordResetCleanupJob` ogni notte alle 03:35 UTC: via i codici scaduti da più di un giorno

**Checkpoint:**
- [x] `PasswordResetIT` (10): flusso completo (vecchia password 401, nuova 200, access token e refresh token precedenti 401); codice valido una volta; email sconosciuta → 204 e nessuna riga; 5 errori salvati e codice consumato; codice malformato o di 96 byte → 422, non 500; una nuova richiesta invalida la precedente; codice scaduto → 422; quarta richiesta → 429 con `Retry-After`, anche per un'email inesistente; password debole → 400; codici cancellati con l'account e dal job; account bloccato dai login sbagliati sbloccato dal reset
- [x] `PasswordResetCodeTest`, `PasswordResetTokenTest`, `PasswordResetServiceTest`; `MigrationIT` con V13; `docs/openapi.json` rigenerato
- [x] dal vivo con Mailpit: l'email arriva (accentate corrette), codice in minuscolo accettato, nel log né email né codice, invio sul thread `pwreset-mail-1` con il `traceId` della richiesta
- [x] `./mvnw clean verify` verde: 51 unit + 152 IT

**Fatto (17/10).** Bug corretti nella review:
- l'app non partiva: `@Id` di `org.springframework.data.annotation` invece di `jakarta.persistence` in `PasswordResetToken`;
- nessun codice era mai valido: `expiresAt.isBefore(expiresAt)` (sempre falso) in `isUsable`;
- un codice malformato saltava BCrypt solo se esisteva un codice aperto: il tempo rivelava l'email; un codice di 32 caratteri accentati dava 500;
- `.env.example` con valori `//` (`MAIL_ENABLED=//` → nessun mailer, avvio fallito) e chiavi `MAIL_HOST/PORT/USER/PASSWORD` non collegate a `spring.mail.*`; nessun timeout SMTP;
- `/actuator/health` a 503 con l'SMTP spento (trovato da `ProdProfileIT`); tag di Mailpit segnaposto (`vX.Y.Z`); import di `infrastructure` nel listener; `@ResponseStatus(NO_CONTENT)` mancante (OpenAPI e client documentavano 200).

Limiti chiusi subito dopo (17/10, su richiesta): richiesta asincrona (tempo uguale con o senza account, BCrypt fuori dal thread HTTP), executor dedicato con `traceId`, limite sui login azzerato dal reset.

**Limite noto:** se l'SMTP fallisce, l'utente non riceve il codice e non lo sa (risposta già 204); lo ritenta dall'app, entro il limite di 3 all'ora. Nessuna coda di reinvio: un codice vecchio non ha valore.

#### M7.3 — Rete mobile: rate limit, compressione, timeout
- [x] **Rate limit su `/auth/**` adatto al CGNAT**, con tre policy configurabili invece di costanti (`@ConfigurationProperties` `geosentinel.rate-limit.*`, così `RateLimitIT` imposta i suoi valori), ognuna con il proprio prefisso di bucket:
  - `login-per-minute` = 30 per IP su login e register (fail-closed): il brute force lo ferma il limite **per account** (`RedisLoginAttemptLimiter`, 10 / 15 min), l'IP è solo un tetto contro gli abusi
  - `refresh-per-minute` = 120 per IP su `/auth/refresh` (bucket `rl:refresh:`). Il refresh ignora il bearer, quindi si conta sempre per IP. Il token opaco ha 256 bit (indovinarlo è impossibile) e la reuse detection copre il furto: qui il limite serve solo contro il flood, e sotto CGNAT molti telefoni fanno refresh insieme
  - `general-per-minute` = 60 per utente autenticato o per IP (invariato). L'app manda sempre il bearer, anche sulle GET pubbliche, così conta per utente e non per IP
  - `logout` resta nella policy di login ma è autenticato, quindi conta per utente
- [x] Compressione delle risposte JSON (risparmia dati mobili): `server.compression.enabled=true`, `mime-types=application/json,application/problem+json`, `min-response-size=1KB`
- [x] GET condizionali sulle rotte pubbliche dei sentieri (liste e GeoJSON possono pesare): `ShallowEtagHeaderFilter` su `/api/v1/trails/**` e `/api/v1/pois/**` → `304 Not Modified` con `If-None-Match`
- [x] `GET /bookings/my` paginato (`PagedModel`, sort in whitelist) come le altre liste: una lista che cresce non va scaricata tutta su mobile
- [x] Contratto dei retry per il client (documentato qui e nella descrizione OpenAPI): `429`, `409 resource-busy` e `503 storage-unavailable` portano `Retry-After` → il client aspetta quel tempo; `Idempotency-Key` generata **una volta per azione** e salvata finché l'azione non riceve una risposta definitiva (anche dopo la chiusura dell'app)
- [x] Contratto del refresh: una sola richiesta di refresh alla volta (il plugin `Auth` di Ktor con `bearer { refreshTokens { } }` la serializza). Due refresh paralleli con lo stesso token fanno scattare la reuse detection (M2.3) e chiudono la sessione: comportamento voluto, va scritto nel contratto

**Checkpoint:** 20 login con password errata da 20 account diversi dallo stesso IP → nessun 429; 11 login sbagliati sullo stesso account → 429; 50 refresh dallo stesso IP con token di utenti diversi → nessun 429; una GET da ~10 KB con `Accept-Encoding: gzip` torna compressa; seconda GET di un sentiero con `If-None-Match` → 304.

**Fatto (30/09, scritto da Claude su richiesta), tutte le voci sopra:**
- `identity/infrastructure/security/RateLimitProperties` (`geosentinel.rate-limit.login-per-minute=30`, `refresh-per-minute=120`, `general-per-minute=60`, `@Validated @Positive`); `RateLimitFilter` costruisce le tre policy dalla configurazione, bucket `rl:auth:`, `rl:refresh:`, `rl:`; login e refresh fail-closed, il resto fail-open. Il filtro gira dopo `BearerTokenAuthenticationFilter`: con il bearer anche le GET pubbliche contano per utente (verificato).
- Compressione in `application.properties` (`server.compression.*`).
- `shared/config/HttpCacheConfig`: `ShallowEtagHeaderFilter` su `/api/v1/trails/*` e `/api/v1/pois/*`. Due trappole:
  - Spring Security manda `Cache-Control: no-store` su tutto, e con `no-store` il filtro non genera l'ETag (e l'app non potrebbe conservare la risposta). In `SecurityConfig` il matcher `PUBLIC_CATALOG` (GET di trails/POI) riceve `Cache-Control: no-cache`; tutte le altre rotte restano `no-store` (`CacheControlHeadersWriter` sul matcher negato);
  - **Tomcat non comprime una risposta con ETag forte**: l'ETag è debole (`setWriteWeakETag(true)`), altrimenti le GET del catalogo non sarebbero mai gzip. `If-None-Match` usa il confronto debole, il 304 arriva anche dopo una risposta compressa.
- `GET /bookings/my` → `PagedModel<BookingResponse>`, `@ParameterObject @PageableDefault(size = 20, sort = "checkIn", DESC)`, whitelist `checkIn`, `checkOut`, `createdAt` (`status` escluso: ordine alfabetico). `BookingRepository.findByUserId(userId, Pageable)` al posto di `findByUserIdOrderByCheckInDesc`. **Cambio incompatibile per l'app** (da lista a pagina): il client va rigenerato.
- Contratti di retry, refresh e GET condizionale nella descrizione del documento OpenAPI (`info.description`); `Retry-After` su 429 / 409 `resource-busy` / 503 `storage-unavailable` era già presente. `docs/openapi.json` rigenerato.
- Test: `RateLimitIT` riscritto (i checkpoint sopra + bucket per utente + refresh che non consuma il bucket del login), `shared/MobileNetworkIT` (gzip di una risposta > 10 KB, 304, ETag nuovo dopo un cambio di stato, `no-store` sulle rotte personali, paginazione e sort 400), `RateLimitFilterTest` (policy per gruppo di rotte), `ProblemDetailContractIT` aggiornato. Helper `exhaustAuthRateLimit()` in `AbstractIntegrationTest`: si ferma quando il bucket è vuoto, non dopo N richieste (il bucket si ricarica durante il ciclo). `./mvnw clean verify` verde due volte: 42 unit + 85 IT.
- Nota: il checkpoint "50 refresh con token di utenti diversi" usa token casuali (401): il limite per IP non guarda il token, e 50 token veri richiederebbero 50 login.

#### M7.4 — Notifiche push (Firebase Cloud Messaging, anche per iOS)
**Obiettivo:** la notifica SOS oggi è solo un log. FCM consegna sia su Android sia su iOS (tramite APNs, configurato nella console Firebase).
- [x] Nuova feature `notification/`:
  - `domain/DeviceToken` (`id`, `userId`, `token varchar(4096) UNIQUE`, `platform ANDROID|IOS`, `lastSeenAt`), estende `AuditableEntity`
  - `application/port/PushNotifier`: `void send(Collection<String> tokens, PushMessage message)` → record `(String title, String body, Map<String,String> data)`
  - `application/DeviceService`: `register(userId, token, platform)` (upsert: lo stesso token passa all'utente che lo registra), `unregister(userId, token)`, `tokensOf(Collection<UUID> userIds)`
  - destinatari per ruolo: `notification` non conosce i ruoli e non usa `UserRepository`. `identity/application/UserQueryService.activeUserIdsWithRole(Role)` (solo `enabled`) → `DeviceService.tokensOf(ids)`. Niente ruolo copiato in `device_tokens` (diventerebbe vecchio al primo cambio di ruolo) e niente **topic FCM** per gli SOS: l'iscrizione al topic va gestita a ogni cambio di ruolo o logout, e un operatore disabilitato continuerebbe a ricevere gli allarmi. Invio diretto ai token con `sendEachForMulticast`, a blocchi di 500 (limite FCM)
  - `infrastructure/push/FcmPushNotifier` (Firebase Admin SDK, credenziali da `GOOGLE_APPLICATION_CREDENTIALS` nel `.env`, mai nel repo) e `LoggingPushNotifier` quando `geosentinel.push.enabled=false` (default in `local` e `test`)
  - token rifiutati da FCM come `UNREGISTERED`/`INVALID_ARGUMENT` → cancellati
  - `DeviceService.deleteAllOf(userId)`, chiamato da `UserAccountService.deleteAccount` (M7.2): i token dei dispositivi sono dati personali, e un account cancellato non deve più ricevere push
- [x] Rotte `POST /api/v1/devices` (`{token, platform}`, 204) e `DELETE /api/v1/devices/{token}` (204); il logout dell'app chiama il `DELETE` prima di `/auth/logout`
- [x] `V9__device_tokens.sql`: tabella + indice su `user_id` + `CHECK` su `platform`
- [x] `SosNotificationListener.deliver`: push agli utenti `RESCUE_OPERATOR` (dati: solo `alertId` e tipo evento, **mai** posizione o note mediche nel payload: si leggono dall'API con il token). Se l'invio fallisce, `notified_at` resta NULL e `SosNotificationRecovery` riprova (M4.3)
- [x] Push all'escursionista su `dispatch` (squadra in arrivo) e `resolve`, con lo stesso schema (`AFTER_COMMIT`, executor dedicato)

**Fatto (30/09, scritto da Claude su richiesta; l'utente aveva creato gli scheletri vuoti), tutte le voci sopra:**
- `notification/`: `domain/DeviceToken` + `DevicePlatform`; `application/port/PushNotifier` (`send` → `Result(delivered, invalidTokens)`, record `PushMessage`); `application/DeviceService` (`register` con upsert `ON CONFLICT (token)`, `unregister` idempotente, `deleteAllOf`, `tokensOf`, `sendToUsers` → `DeliveryReport`); `infrastructure/persistence/DeviceTokenRepository`; `infrastructure/push/FcmPushNotifier` (blocchi da 500, priorità alta Android/APNs) e `LoggingPushNotifier`; `web/DeviceController` + `dto/DeviceDtos`.
- `emergency`: `SosNotificationListener.deliver` manda la push ai `RESCUE_OPERATOR` attivi (`UserQueryService.activeUserIdsWithRole`); nuovo `SosAlertStatusChangedEvent` pubblicato da `dispatchTeam`/`resolveAlert` → push all'escursionista (`SOS_DISPATCHED`/`SOS_RESOLVED`, best effort, senza recupero). Payload: solo `type` e `alertId`.
- `UserAccountService.deleteAccount` chiama `DeviceService.deleteAllOf`.
- Scelte diverse dal piano:
  - `token varchar(1024)`, non 4096: un indice B-tree rifiuta righe oltre ~2700 byte (500 su un token lungo). I token FCM sono ~160 caratteri;
  - credenziali da `geosentinel.push.credentials-file=${GOOGLE_APPLICATION_CREDENTIALS:}`: il `.env` diventa property di Spring, non variabile d'ambiente, quindi `GoogleCredentials.getApplicationDefault()` non lo vedrebbe. `PUSH_ENABLED` (default `false`) accende FCM;
  - SOS notificato se almeno un dispositivo ha ricevuto la push (riprovare finché tutti la ricevono ripeterebbe la push agli altri ogni 30 s per un solo token guasto); nessun dispositivo registrato → notificato con un warning (niente loop);
  - token cancellati anche per `SENDER_ID_MISMATCH` (token di un altro progetto Firebase).
- Bug trovati nella verifica:
  - escludere Firestore/Storage toglieva `google-http-client-jackson2`, che `FirebaseOptions` usa di default: compilava, gli IT erano verdi (usano il notifier finto), ma l'avvio con le push accese andava in `NoClassDefFoundError`. Trovato con una prova diretta di `FcmPushNotifier` (account di servizio finto); dipendenza aggiunta;
  - NPE in `FcmPushNotifier`: `getMessagingErrorCode()` è null per gli errori non FCM (credenziali, rete) e `Set.of(...).contains(null)` lancia;
  - `httpclient5` (arriva con firebase-admin) fa scegliere Apache HttpClient a `RestClient.create()`, che ripete da solo i 429 dopo `Retry-After`: `RateLimitIT` e `ProblemDetailContractIT` ricevevano 400 invece di 429. `AbstractIntegrationTest.call` usa `JdkClientHttpRequestFactory`.
- Test: `notification/SosPushIT` (8, `PushNotifier` finto `@Primary`), `MigrationIT` (V9), TRUNCATE di `device_tokens`. `docs/openapi.json` rigenerato (`registerDevice`, `unregisterDevice`). `./mvnw clean verify` verde due volte: 42 unit + 93 IT.
- Resta la prova manuale con Firebase e un dispositivo: richiede l'app KMP (senza app non esistono token FCM).

**Checkpoint:** `SosPushIT` con un `PushNotifier` finto (bean di test): raise → gli operatori ricevono un messaggio con solo `alertId`; notifier che lancia → `notified_at` NULL e il recupero la rinvia; token `UNREGISTERED` → cancellato. Prova manuale con un dispositivo vero e il progetto Firebase.

#### M7.5 — Media dal telefono
- [x] **HEIC** (predefinito della fotocamera iOS). **Deciso (30/09): l'app converte in JPEG, backend invariato.** Scelta consigliata: l'app converte in **JPEG** e ridimensiona (lato lungo ≤ 2048 px, qualità ~80%) prima dell'upload: più leggero su rete mobile, visibile ovunque (Android non legge HEIC in tutte le versioni). Il backend resta com'è; se invece si vuole accettare HEIC: `image/heic` nella whitelist di `MediaPolicy` + magic bytes (`ftyp` a offset 4 con brand `heic`/`heix`/`mif1`) + test
- [x] Contratto dell'upload per il client (Ktor `submitFormWithBinaryData`): tutti i `formFields` senza modifiche (ordine libero), poi `file` per ultimo; `Content-Type` della parte file uguale a quello dichiarato; dopo il 204 di MinIO chiamare `/confirm`; upload scaduto (10 min) → nuova `upload-requests`
- [x] `MINIO_PUBLIC_ENDPOINT` = indirizzo con cui il **telefono** raggiunge MinIO (la firma include l'host): emulatore Android `http://10.0.2.2:9000`, simulatore iOS `http://localhost:9000`, telefono vero `http://<IP del PC>:9000`, produzione il dominio HTTPS dello storage
- [x] `MediaUploadIT` (rimasto facoltativo in M6): con il client mobile diventa il test di regressione del contratto di upload

**Checkpoint:** foto scattata dall'app su emulatore Android e simulatore iOS → `READY` e visibile nella segnalazione; `MediaUploadIT` verde in CI.

**Fatto (01/10, scritto da Claude su richiesta):**
- Contratto dell'upload nella descrizione del documento OpenAPI (`info.description`, sezione *Media upload contract*): tipi ammessi e conversione HEIC → JPEG, POST multipart senza bearer, `formFields` invariati e `file` per ultimo, `Content-Length` obbligatorio, errori di MinIO (400 `EntityTooLarge`, 403), confirm (`READY` / `QUARANTINED`, 422 senza file, ripetibile), scadenza a `expiresAt`. `docs/openapi.json` rigenerato.
- `.env.example`: gli indirizzi di `MINIO_PUBLIC_ENDPOINT` per emulatore, simulatore, telefono (firewall di Windows sulla 9000) e produzione; con l'host sbagliato MinIO risponde 403 `SignatureDoesNotMatch`.
- `media/MediaUploadIT` (5 test): container `pgsty/minio` (stesso tag del compose, `GenericContainer`: nessuna dipendenza nuova), bucket creato dal test, endpoint e public endpoint dal `@DynamicPropertySource`. Upload di un HIKER sulla sua segnalazione → `READY`, un altro HIKER la vede in `/attached` e la scarica (byte identici, `image/jpeg`, `Content-Disposition: attachment`); PARK_MANAGER su un sentiero; file più grande del dichiarato → 400 `EntityTooLarge` da MinIO e confirm 422; `Content-Type` o `key` manomessi → 403; testo dichiarato JPEG → `QUARANTINED`, oggetto cancellato, confirm ripetuto con lo stesso esito.
- **Trappola trovata:** MinIO rifiuta un POST policy chunked (`400 EmptyRequestBody`): serve il `Content-Length`. Nel contratto per l'app; con Ktor e parti `ByteArray` la lunghezza è nota. Nel test `BufferingClientHttpRequestFactory`.
- Backend e `MediaPolicy` invariati (HEIC lo converte l'app). `./mvnw clean verify` verde: 42 unit + 98 IT.
- Resta il checkpoint manuale con l'app (emulatore e simulatore).

#### M7.6 — Ambiente raggiungibile dai dispositivi
- [x] **Sviluppo:** documentare in `.env.example` e nel README gli indirizzi per emulatore/simulatore/telefono (vedi M7.5). HTTP in chiaro solo in sviluppo: Android richiede un `network_security_config` che lo permetta verso `10.0.2.2` / IP del PC; iOS `NSAllowsLocalNetworking` in `Info.plist` (configurazione dell'app, non del backend)
- [x] **Staging per i tester** (iOS rifiuta HTTP fuori dalla rete locale, App Transport Security):
  - profilo `application-prod.properties`: logging `INFO`, springdoc spento, `server.forward-headers-strategy=native` (senza, dietro il proxy tutti i client hanno lo stesso IP e il rate limit per IP li blocca insieme)
  - reverse proxy con TLS automatico (es. Caddy) davanti all'app e a MinIO, due sottodomini (`api.` e `media.`); nel compose un profilo `prod` con il proxy e il servizio `backend`
  - segreti solo da variabili d'ambiente del server, mai file nel repo; `JWT_SECRET` diverso da quello di sviluppo
  - backup giornaliero di Postgres (`pg_dump` programmato) e del bucket
- [x] `GET /actuator/health` per il monitoraggio esterno (già pubblico, senza dettagli)

**Checkpoint:** l'app di test su un iPhone fisico, fuori dalla rete di casa, fa login, apre un sentiero e carica una foto via HTTPS.

**Fatto (01/10, scritto da Claude su richiesta):**
- `application-prod.properties`: `server.forward-headers-strategy=native`, springdoc spento in modo esplicito, logging `INFO`. Tomcat legge `X-Forwarded-*` solo da un proxy interno (reti private e loopback); Caddy non si fida degli `X-Forwarded-For` in arrivo e li riscrive con l'IP vero.
- `docker-compose.prod.yml` (file separato invece di un profilo: nel compose di sviluppo Postgres, Redis e MinIO pubblicano le porte, qui solo Caddy) + `deploy/Caddyfile`: `API_DOMAIN` → backend, `MEDIA_DOMAIN` → MinIO (Host inoltrato invariato, le firme tornano), TLS automatico, redirect HTTP → HTTPS, console MinIO spenta, backend senza porte e con healthcheck, niente `env_file` (solo le chiavi necessarie), segreti obbligatori `${VAR:?}` da `--env-file .env.prod` sul server.
- Backup: `db-backup` (`pg_dump -Fc` giornaliero in `BACKUP_DIR/db`, file `.tmp` rinominato solo se completo, conservazione `BACKUP_KEEP_DAYS`) e `media-backup` (`mc mirror --remove` in `BACKUP_DIR/media`). Restano sul server: copiarli altrove è compito di chi gestisce il server.
- README scritto (prima c'era solo il titolo): sviluppo, indirizzi per emulatore/simulatore/telefono, `network_security_config` / `NSAllowsLocalNetworking`, deploy, backup e ripristino. `.gitignore`/`.dockerignore`: `.env.*` (tranne `.env.example`) e `backups/`.
- Test: `shared/ProdProfileIT` (profilo `prod` sopra `test`): due IP in `X-Forwarded-For` hanno bucket del rate limit separati (senza `native` sarebbero lo stesso bucket di 127.0.0.1), `Location` in https con `X-Forwarded-Proto`, `/v3/api-docs` 404, health senza `components`/`details`. `./mvnw clean verify` verde: 42 unit + 101 IT.
- Prova dal vivo dello stack prod sul PC (`api.localhost` / `media.localhost`, porte 8088/8443, CA locale di Caddy verificata, non `verify=False`): health via TLS, redirect 308, Swagger 404, register → login → sentieri → segnalazione (`Location` https) → upload presigned via Caddy → `READY` → download con byte identici; `X-Forwarded-For` falsificato non aggira il limite (429 alla 29ª richiesta); dump valido e **ripristinato** in un DB temporaneo (utenti, segnalazione, media, Flyway V9), foto presente nella copia del bucket. Stack e volumi di prova rimossi.
- Limite noto: su una porta HTTPS diversa da 443 la `Location` perde la porta (Caddy non manda `X-Forwarded-Port`); in produzione si usa la 443.
- **Deciso (01/10): il progetto (universitario) resta in locale sul PC.** Lo stack prod è pronto e provato ma non si usa; il checkpoint con l'iPhone fisico fuori dalla rete di casa non si applica. L'app si prova su emulatore Android e simulatore iOS con il compose di sviluppo (indirizzi nel README).

#### M7.7 — Test e review
- [x] `MediaUploadIT` (M7.5). Già fatti: `UserAccountIT` (M7.2), `RegistrationAgeIT` (M7.2.1), `SosPushIT` (M7.4), test del contratto OpenAPI (M7.1), `RateLimitIT` aggiornato (M7.3)
- [x] Review con la catena del §5 (riga 7)

**Fatto (01/10, scritto da Claude su richiesta):**
- Stabilità: `SchedulingConfig` con `@ConditionalOnProperty(geosentinel.scheduling.enabled, matchIfMissing = true)`, spento in `application-test.properties`. I job (`SosNotificationRecovery` ogni 30 s, `MediaCleanupJob`, pulizie notturne) non girano più da soli durante gli IT; i test che li riguardano chiamano il metodo direttamente.
- `updateCapacity` → 409 `capacity-exceeded` se la nuova capacità è sotto gli ospiti confermati di una notte da stanotte in poi (`BookingRepository.lastConfirmedCheckOut` + `maxNightlyOccupancy`, sotto il lock del rifugio). Le prenotazioni passate e cancellate non contano. Chiude il limite noto di M4.2: per un rifugio danneggiato le prenotazioni in eccesso vanno comunque gestite a mano (non esiste un'API per cancellare quelle altrui).
- Review di sicurezza e Java del codice di M7 (inline, sul diff da M7.1). Due problemi reali, corretti:
  - **access token degli altri dispositivi validi dopo la cancellazione dell'account e il cambio password** (fino a 15 min): un account cancellato poteva ancora prenotare, lanciare SOS e caricare foto, che `MediaService.deleteAllOf` non avrebbe più visto. Ora `TokenBlacklist.revokeAllIssuedUntil(userId, until)`: chiave Redis `bl:user:<id>` = secondo epoch, TTL = `access-ttl`; `JtiBlacklistValidator` controlla `jti` e revoca per utente con un solo `MGET` (fail-closed). Inclusiva al secondo (`iat` ha la precisione del secondo): la cancellazione revoca fino a `now`, il cambio password fino a `now - 1 s`, così la sessione nuova resta valida;
  - **`changePassword` senza limite sui tentativi**: con un access token rubato si poteva indovinare la password attuale a 60 tentativi al minuto e prendere l'account. Ora lo stesso limite per account del login (`LoginAttemptLimiter`, 10 ogni 15 min, bucket condiviso) → 429 + `Retry-After`.
- Rivisti senza problemi: payload e log delle push (solo `type` + `alertId`), cancellazione dei token invalidi, `unregister` limitato all'utente, rate limit e cache HTTP (M7.3), contratto media (M7.5), profilo prod (M7.6).
- Limite accettato: nessun tetto al numero di token push per utente (il rate limit generale ne limita la crescita a 60/min).
- Test: `RefugeCapacityIT` (2), `UserAccountIT` esteso (token di un altro dispositivo → 401 dopo cancellazione e cambio password, 429 sui tentativi della password attuale). `./mvnw clean verify` verde due volte di fila: 42 unit + 104 IT.
- Verifiche finali: `/security-review` sulle modifiche di M7.6–M7.7 (revoca per utente fail-closed, header inoltrati solo da Caddy, query con parametri bound): nessuna vulnerabilità. AgentShield: B (80/100), tutti avvisi testuali su `CLAUDE.md`; segreti, permessi, hook e MCP a 100.

**Checkpoint M7:**
- [x] `./mvnw verify` verde (42 unit + 104 IT, 01/10)
- [x] client Kotlin generato dal `docs/openapi.json` committato (M7.1, generato e controllato fuori dal progetto dell'app)
- [ ] flusso completo dall'app su Android e iOS: registrazione → login → sentieri vicini → segnalazione con foto → SOS → notifica push all'operatore → cancellazione dell'account. **Richiede l'app KMP**: ogni passo è già coperto lato backend dagli IT (`AuthFlowIT`, `TrailSpatialQueryIT`, `MediaUploadIT`, `SosPushIT`, `UserAccountIT`)

---

### Milestone 8 — Rischio frane: calcolo parallelo in C++ (MPI + pthread)

> Pianificata il 17/10. Decisioni in ADR-0022 (architettura) e ADR-0023 (modello). Il codice lo scrivi tu, come sempre.

**Obiettivo:** stimare **dove una frana è probabile** (suscettibilità, con uno scenario di pioggia) e **dove arriverebbe** (propagazione), poi capire **quali sentieri attraversa**. Il calcolo pesante è un programma C++ parallelo (MPI tra processi, pthread dentro ogni processo); il backend Java mette in coda i calcoli, legge i risultati e li espone all'app.

**Perché qui ha senso il calcolo parallelo** (stime su un PC a 12 core, da misurare in M8.8):
- suscettibilità su tutta la Calabria a 30 m: circa 16,7 milioni di celle × 1000 campioni Monte Carlo ≈ 1,7·10¹⁰ valutazioni del fattore di sicurezza. Qualche minuto su un core, decine di secondi su 12;
- propagazione su una finestra di 5 × 5 km a 10 m: 250 000 celle × migliaia di passi dell'automa cellulare.

**Contesto e limiti, da dire anche nell'app:** è una **stima indicativa**, non sostituisce il PAI dell'Autorità di Bacino né i bollettini della Protezione Civile. Il modello non conosce la geologia locale (parametri del suolo per intervalli, ADR-0023). Per questo **nessuna azione automatica sui sentieri**: il backend calcola e segnala, lo stato `OPEN/WARNING/CLOSED` lo decide un `PARK_MANAGER`.

**Architettura** (ADR-0022):
```
PARK_MANAGER verifica una segnalazione LANDSLIDE        ADMIN lancia una suscettibilità
        │ HazardVerifiedEvent (stessa transazione)              │ POST .../susceptibility-runs
        ▼                                                        ▼
   simulation_jobs (PENDING)  ◄──── unico contratto tra Java e C++ ────►  landslide-worker (C++)
                                                                     claim con FOR UPDATE SKIP LOCKED
                                                                     mpirun -np P landslide-sim (T thread)
                                                                     zone in landslide_zones, job DONE
        ▲
   LandslideResultIngestJob (@Scheduled): ST_Intersects(zone, trails.path) → trail_landslide_risk
        ▼
   GET /api/v1/landslide-risk/...  →  app (livello di rischio sulla mappa)
```
- **Niente JNI, niente chiamate HTTP tra Java e C++:** il contratto è il database. Un crash del calcolo non tocca il backend (e quindi l'SOS).
- **Niente porta in `application`:** Java non chiama il worker, scrive una riga. Una porta qui sarebbe un'interfaccia senza un secondo sistema da sostituire (D2).
- **Il worker non sa cosa sia un sentiero:** produce poligoni. L'intersezione con i sentieri la fa PostGIS, lato Java.

**Ordine:** M8.1 → M8.2 → M8.3 → M8.4 → M8.5 → M8.6 → M8.8, con **M8.7 (lato Java) in parallelo** da subito dopo aver fissato le tabelle (V14): si prova con zone finte inserite in SQL, senza il C++.

#### M8.1 — Dati: DEM, inventario frane, parametri
**Obiettivo:** dati di ingresso riproducibili da uno script, mai committati (pesano centinaia di MB).
- [ ] `tools/dem-prep/` (accanto a `tools/osm-import`): script che scarica, ritaglia sulla Calabria, riproietta in **EPSG:32633** (UTM 33N, metri) e scrive GeoTIFF in `${SIM_DATA_DIR}` (volume Docker, fuori dal repo, in `.gitignore` e `.dockerignore`)
  - **DEM 30 m** (Copernicus GLO-30) per la suscettibilità di tutta la regione
  - **DEM 10 m** (TINITALY, INGV) per la propagazione, letto a finestre
  - licenze e attribuzioni da verificare al download e da scrivere in `tools/dem-prep/README.md` (come i crediti delle foto, V12)
- [ ] Inventario delle frane IFFI (ISPRA) per la Calabria, solo per la validazione (M8.8). Non entra nel calcolo
- [ ] `simulation/config/soil-params.json`: intervalli dei parametri (ADR-0023), un'unica classe di suolo per partire. Valori iniziali da letteratura (SINMAP), da calibrare in M8.8
- **Vincolo:** il calcolo lavora in metri (32633); il passaggio a EPSG:4326 lo fa **PostGIS** (`ST_Transform`) all'inserimento, mai il C++. Così l'ordine lon/lat (D10) resta in un solo punto e si evita la trappola dell'ordine degli assi di GDAL 3 per EPSG:4326.

**Checkpoint:**
- [ ] lo script è idempotente: rilanciato non riscarica e produce gli stessi file (checksum)
- [ ] `gdalinfo` sui file: EPSG:32633, risoluzione 30 m e 10 m, nodata definito
- [ ] `ST_Transform(ST_SetSRID(ST_MakePoint(x, y), 32633), 4326)` di un punto noto (es. un rifugio del seed) torna alle sue coordinate entro 1 m

#### M8.2 — Progetto C++: struttura, build, decomposizione del dominio
**Obiettivo:** scheletro compilabile e testato, con la decomposizione e lo scambio dei bordi già verificati prima di metterci un modello sopra.

Struttura (nuova cartella del repo backend, progetto CMake indipendente da Maven):
```
simulation/
├── CMakeLists.txt            # C++20, -Wall -Wextra -Werror, target: landslide-sim, landslide-worker, test
├── Dockerfile                # build: ubuntu:24.04 + build-essential cmake libopenmpi-dev libgdal-dev libpqxx-dev
│                             # runtime: solo le librerie, utente non root
├── config/soil-params.json
├── include/geosentinel/sim/
│   ├── Grid.hpp              # griglia locale: righe proprie + 1 riga di halo sopra e sotto, row-major
│   ├── Decomposition.hpp     # strisce di righe per rank (MPI), blocchi di righe per thread
│   ├── HaloExchange.hpp      # MPI_Sendrecv delle righe di bordo (solo il thread principale)
│   ├── ThreadPool.hpp        # T pthread creati una volta, pthread_barrier_t tra le fasi
│   ├── Rng.hpp               # generatore counter-based: (seed, cella, campione) → numero
│   ├── RasterIo.hpp          # GDAL: lettura di una finestra per rank, scrittura GeoTIFF dal rank 0
│   └── Polygonize.hpp        # GDALPolygonize + semplificazione → GeoJSON in EPSG:32633
├── src/   (sim/, worker/)
└── test/                     # GoogleTest + CTest; i test MPI girano con mpirun -np 1, 2, 4
```
Contratti:
- [ ] `Decomposition::forRank(int rows, int ranks, int rank) → RowRange {first, count}`: strisce contigue, differenza massima di una riga tra i rank
- [ ] `HaloExchange::exchange(Grid<T>&)`: riga 0 ↔ rank sopra, ultima riga ↔ rank sotto, `MPI_PROC_NULL` ai bordi del dominio
- [ ] MPI inizializzato con `MPI_Init_thread(..., MPI_THREAD_FUNNELED, ...)`: **solo il thread principale chiama MPI**; se il livello concesso è inferiore, uscita con errore
- [ ] I/O: ogni rank legge la sua finestra con `RasterIO` (lettura parallela di un file locale), il rank 0 raccoglie con `MPI_Gatherv` e scrive. Nessun rank tiene in memoria la griglia intera, tranne il rank 0 durante la scrittura
- [ ] `Rng`: risultato funzione solo di `(seed, indice globale della cella, indice del campione)` (es. Philox o splitmix64 sulla chiave combinata), mai di rank o thread: è ciò che rende il risultato indipendente da P e T
- [ ] Sviluppo su Windows: build e test in **WSL2 o nel container** (pthread e OpenMPI sono Linux; MS-MPI non si usa)
- [ ] CI: nuovo job `simulation` in `.github/workflows/ci.yml` (Ubuntu): `cmake --build` + `ctest`, con i test MPI a `-np 4` (i runner hanno 4 core)

**Trappole:**
- **OpenMPI in Docker:** `/dev/shm` è di 64 MB e il trasporto a memoria condivisa fallisce o rallenta → `shm_size: 1gb` nel compose. OpenMPI rifiuta di girare come root: l'utente non root del Dockerfile serve anche a questo.
- **False sharing:** accumulatori per thread allineati a 64 byte (`alignas(64)`), altrimenti con 12 thread lo speedup crolla senza un motivo apparente.
- `pthread_barrier_t` non esiste su macOS: va bene, il calcolo gira solo su Linux.

**Checkpoint:**
- [ ] `ctest` verde nel container e in CI
- [ ] test dell'halo: griglia con valore = indice globale della riga, dopo `exchange` le righe di halo di ogni rank contengono le righe giuste, con `-np 1, 2, 4`
- [ ] test della decomposizione: unione dei range = tutte le righe, nessuna sovrapposizione, anche con più rank che righe
- [ ] lettura e riscrittura di un GeoTIFF con `-np 4`: file identico all'originale (byte per byte sui dati, georeferenziazione uguale)

#### M8.3 — Preprocessing del terreno: pendenza e area drenata
**Obiettivo:** gli strati che servono al modello, calcolati una volta per DEM e salvati come GeoTIFF in `${SIM_DATA_DIR}/derived/`.
- [ ] **Pendenza** θ con il metodo di Horn (stencil 3×3): parallela, MPI (halo di 1 riga) + pthread (blocchi di righe). Celle nodata e bordo del dominio → nodata
- [ ] **Riempimento delle depressioni** (priority-flood) e **direzioni di deflusso D8**: sequenziali
- [ ] **Area drenata specifica** `a` (area a monte / larghezza della cella, in metri): accumulo D8 in ordine topologico, **sequenziale**. L'accumulo dipende da tutto il bacino a monte e non si decompone a strisce in modo semplice: farlo in parallelo è un'estensione facoltativa (M8.8), non un requisito
- [ ] Sottocomando `landslide-sim prepare --dem <file> --out <dir>`

**Checkpoint:**
- [ ] test su un piano inclinato sintetico (pendenza nota, es. 30°): θ = 30° ± 0,01° in tutte le celle interne, con `-np 1` e `-np 4`
- [ ] test su un cono rovesciato sintetico: tutta l'area drena verso il centro e l'accumulo al centro = numero di celle valide
- [ ] su un DEM vero, pendenza confrontata con `gdaldem slope -alg Horn`: differenza massima < 0,01°

#### M8.4 — Suscettibilità: Monte Carlo del fattore di sicurezza (MPI + pthread)
**Obiettivo:** per ogni cella, la probabilità che il versante ceda con uno scenario di pioggia dato, `P(FS < 1)`.

Modello (pendio indefinito + idrologia stazionaria, come SINMAP; ADR-0023):
```
w  = min(1, R·a / (T·sin θ))                       # saturazione relativa
FS = (C + cos θ · (1 − w·r) · tan φ) / sin θ        # r = ρw/ρs ≈ 0,5
```
- `θ` e `a` da M8.3; `R` (ricarica, m/giorno) dallo scenario: `R = pioggia_mm_24h / 1000 · infiltrazione`
- `C` (coesione adimensionale), `φ` (angolo d'attrito) e `T` (trasmissività) **campionati** negli intervalli di `soil-params.json` con `Rng(seed, cella, campione)`
- `P = (campioni con FS < 1) / N`; celle piatte (θ < 1°) → `P = 0`

Contratti:
- [ ] `landslide-sim susceptibility --derived <dir> --rain-mm <x> --samples <N> --seed <s> --out <dir>` → `probability.tif` (float32) + `zones.geojson` (EPSG:32633)
- [ ] Parallelismo: strisce MPI, blocchi di righe ai pthread; nessuno scambio di halo (ogni cella è indipendente), solo `Gatherv` finale. Il tempo di calcolo deve dominare quello di I/O e poligonizzazione: da misurare (M8.8)
- [ ] Classi: `MEDIUM` se `0,1 ≤ P < 0,5`, `HIGH` se `P ≥ 0,5` (soglie in `soil-params.json`). Poligoni con `GDALPolygonize` sulle classi, semplificati a 10 m, scartati sotto 2 celle. `LOW` non si scrive (sarebbe quasi tutta la regione)
- [ ] Ogni feature GeoJSON ha `kind` (`SUSCEPTIBILITY_MEDIUM`/`SUSCEPTIBILITY_HIGH`) e `max_value` (P massima nel poligono)

**Checkpoint:**
- [ ] test del fattore di sicurezza su valori calcolati a mano (3 casi: secco, saturo, pendio piatto)
- [ ] **riproducibilità:** stesso seed → `probability.tif` identico byte per byte con (P, T) = (1, 1), (4, 1), (2, 6), (12, 1)
- [ ] più pioggia → P mai minore in nessuna cella (test su due scenari)
- [ ] piano inclinato sintetico: P cresce con la pendenza, celle sotto 1° a zero

#### M8.5 — Propagazione: automa cellulare della colata (MPI + pthread)
**Obiettivo:** a partire da un punto d'innesco (una frana verificata, o un "e se" del gestore), l'area che la colata invade.

Modello (semplificazione ispirata a SCIDDICA-T, ADR-0023): celle quadrate, vicinato di von Neumann (4 vicini), sottostati `z` (quota del DEM) e `h` (spessore della colata); a ogni passo i deflussi verso i vicini si calcolano con l'**algoritmo di minimizzazione delle differenze** sulla quantità `z + h`, al netto di uno spessore di **aderenza** `p_adh` che resta nella cella, e si scalano con un fattore di rilassamento `p_r`.
- [ ] Finestra: DEM 10 m, 5 × 5 km centrata sull'innesco (letta con `RasterIO`, non l'intero DEM)
- [ ] Innesco: celle entro `raggio` dal punto con spessore iniziale `h0` (dallo scenario o dal gestore)
- [ ] **Passo in due fasi**, con barriere tra i thread:
  1. ogni thread calcola i deflussi delle sue celle (array dei deflussi, 4 valori per cella)
  2. barriera → il thread principale scambia l'**halo dei deflussi** con i rank vicini (MPI) → barriera
  3. ogni thread aggiorna `h` delle sue celle: `h += Σ entranti − Σ uscenti`, sommando gli entranti **sempre nello stesso ordine dei vicini** (così il risultato non dipende da P e T)
- [ ] Arresto: deflusso totale < ε (`MPI_Allreduce`) o numero massimo di passi
- [ ] Ottimizzazione facoltativa: calcolo solo nel rettangolo delle celle attive (la colata occupa una piccola parte della finestra)
- [ ] `landslide-sim runout --dem10 <file> --lon --lat --radius-m --h0-m --out <dir>` → `max_thickness.tif` + `zones.geojson` (`kind = RUNOUT`, area dove lo spessore massimo ha superato una soglia, es. 5 cm)
- Il punto d'innesco arriva in EPSG:4326 dal backend: la conversione in 32633 la fa il **worker** con una query PostGIS (`ST_Transform`) prima di lanciare il calcolo, non GDAL

**Checkpoint:**
- [ ] **conservazione della massa:** volume totale costante a ogni passo, errore relativo < 1e-9
- [ ] risultato identico con (P, T) = (1, 1), (4, 1), (2, 6)
- [ ] piano inclinato: la colata scende lungo la pendenza massima e non risale; superficie piana: si allarga in modo simmetrico
- [ ] test di regressione D10 sul punto d'innesco: un innesco a est di un crinale noto finisce nel versante est

#### M8.6 — Worker: coda dei job, ciclo di vita, scrittura dei risultati
**Obiettivo:** il processo che collega la coda nel database al calcolo MPI.

- [ ] `landslide-worker` (C++, libpqxx, **non MPI**): ciclo che prende un job, lancia `mpirun -np ${SIM_PROCESSES} landslide-sim ... --threads ${SIM_THREADS}` e aspetta. Separato dal calcolo così `landslide-sim` si testa senza database, e nessun rank MPI resta in attesa a vuoto tra un job e l'altro (OpenMPI consuma CPU mentre aspetta)
- [ ] **Presa di un job** (atomica, più worker non prendono lo stesso job):
  ```sql
  UPDATE simulation_jobs SET status = 'RUNNING', attempts = attempts + 1, worker_id = $1,
         started_at = now(), lease_until = now() + interval '10 minutes'
   WHERE id = (SELECT id FROM simulation_jobs
                WHERE (status = 'PENDING' OR (status = 'RUNNING' AND lease_until < now()))
                  AND attempts < 3
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED LIMIT 1)
  RETURNING id, type, ...
  ```
- [ ] **Lease:** un thread del worker (con la sua connessione) allunga `lease_until` ogni minuto finché il calcolo gira. Worker morto → il lease scade → un altro worker riprende il job
- [ ] **Chiusura, in una transazione:** `DELETE` delle zone del job (un retry non duplica) → `INSERT` delle zone con `ST_Multi(ST_Transform(ST_GeomFromWKB($wkb, 32633), 4326))` → `UPDATE ... SET status = 'DONE', finished_at, runtime_ms, processes, threads WHERE id = $1 AND worker_id = $me`. Se l'`UPDATE` tocca 0 righe (lease perso) → rollback
- [ ] **Errore:** `FAILED` se `attempts >= 3`, altrimenti di nuovo `PENDING`; in `error` un messaggio breve (mai stringhe di connessione o stack)
- [ ] **Permessi minimi:** ruolo `geosentinel_sim` creato da V14 senza login né password; `GRANT SELECT` e `UPDATE` (solo sulle colonne di stato) su `simulation_jobs`, `INSERT`/`DELETE` su `landslide_zones`, nient'altro (non legge `users`, `sos_alerts`, …)
- [ ] Password del ruolo dal `.env` (`SIM_DB_PASSWORD`, aggiunta a `.env.example`): servizio `landslide-db-init` nel compose (come `minio-init`) che aspetta che V14 esista e poi fa `ALTER ROLE geosentinel_sim LOGIN PASSWORD ...`. **Trappola:** `psql -c` non sostituisce le variabili `:'pw'`, servono `-v pw=...` con il comando da stdin (heredoc), altrimenti la password va in chiaro nella riga di comando o la query si rompe
- [ ] Compose: servizio `landslide-worker` sotto `profiles: ["sim"]`, `shm_size: 1gb`, volume `${SIM_DATA_DIR}` in sola lettura (output in una cartella temporanea del container), `depends_on` postgres healthy + `landslide-db-init` completato

**Checkpoint:**
- [ ] due worker accesi, 10 job in coda: ogni job eseguito una volta sola
- [ ] `kill -9` del worker durante un calcolo: dopo la scadenza del lease il job riparte su un altro worker e termina `DONE`, senza zone duplicate
- [ ] con le credenziali di `geosentinel_sim`: `SELECT * FROM users` → permesso negato
- [ ] `docker compose --profile sim up -d`: un job di suscettibilità inserito a mano diventa `DONE` con zone in 4326 che cadono in Calabria

#### M8.7 — Backend Java: feature `landslide`
**Obiettivo:** mettere in coda i calcoli, trasformare le zone in rischio per sentiero, esporlo all'app.

`V14__landslide.sql` (V13 è il reset della password, M7.2.2):
| Tabella | Contenuto |
|---|---|
| `simulation_jobs` | id uuid PK, type CHECK `SUSCEPTIBILITY/RUNOUT`, status CHECK `PENDING/RUNNING/DONE/FAILED`, `source_hazard_id` FK `hazard_reports(id)` NULL, `requested_by` FK `users(id)` NULL, **parametri in colonne tipizzate** (`rain_mm_24h`, `samples`, `seed`, `trigger_location geometry(Point,4326)`, `radius_m`, `h0_m`; niente `jsonb`: con Jackson 3 il mapping JSON di Hibernate va verificato, e le colonne si validano con `CHECK`), `attempts int NOT NULL DEFAULT 0`, `lease_until`, `worker_id varchar(64)`, `started_at`, `finished_at`, `error varchar(500)`, `runtime_ms`, `processes`, `threads`, `processed_at timestamptz NULL` (ingestione Java), audit cols. `CHECK` sui parametri obbligatori per tipo; `UNIQUE (source_hazard_id) WHERE type = 'RUNOUT'` (una propagazione per segnalazione, anche con due verifiche concorrenti); indici parziali `(created_at) WHERE status = 'PENDING'` e `(finished_at) WHERE status = 'DONE' AND processed_at IS NULL` |
| `landslide_zones` | id uuid `DEFAULT gen_random_uuid()` (unica tabella con default: le righe le scrive il worker, non Hibernate; `gen_random_uuid()` è nel core da PG 13, niente `pgcrypto`), `job_id` FK con indice, kind CHECK `SUSCEPTIBILITY_MEDIUM/SUSCEPTIBILITY_HIGH/RUNOUT`, `max_value double precision`, `geom geometry(MultiPolygon,4326) NOT NULL` + GiST |
| `trail_landslide_risk` | PK `(job_id, trail_id)`, `trail_id` FK con indice, level CHECK `MEDIUM/HIGH`, `exposed_length_m double precision NOT NULL`, `exposed_path geometry(MultiLineString,4326) NOT NULL`, `computed_at` |
| ruolo | `geosentinel_sim` `NOLOGIN` (blocco `DO` con `IF NOT EXISTS`: i ruoli valgono per tutto il cluster) + i `GRANT` di M8.6 |

Struttura (come le altre feature):
```
landslide/
├── domain/        SimulationJob, SimulationType, SimulationStatus, ZoneKind, RiskLevel
├── application/   LandslideRiskService, LandslideResultIngestJob, dto/LandslideCommands
├── infrastructure/
│   ├── persistence/  SimulationJobRepository, TrailLandslideRiskRepository (query native di esposizione)
│   └── event/        HazardVerifiedListener
└── web/           LandslideRiskController (/api/v1/landslide-risk), dto/ LandslideDtos, LandslideMapper
```
- [ ] **Innesco dalla verifica:** `HazardReportService` pubblica `HazardVerifiedEvent(hazardId, type, lon, lat)` (record in `trail/domain`, dati e non entità) alla transizione `SUBMITTED → VERIFIED`. `HazardVerifiedListener` con `@TransactionalEventListener(phase = BEFORE_COMMIT)`: se `type == LANDSLIDE`, inserisce il job `RUNOUT` **nella stessa transazione** (verifica e job insieme o niente). `trail` non dipende da `landslide`
- [ ] **Ingestione:** `LandslideResultIngestJob` ogni 30 s (`@Scheduled`, spento nei test come gli altri) prende i job `DONE` con `processed_at IS NULL` e, per ognuno, calcola l'esposizione in una sola query:
  ```sql
  INSERT INTO trail_landslide_risk (...)
  SELECT t.id, z.job_id, <livello da kind>, ST_Length(ST_Intersection(t.path, z.geom)::geography), ...
    FROM landslide_zones z JOIN trails t ON ST_Intersects(t.path, z.geom)
   WHERE z.job_id = :jobId
  ```
  aggregando per sentiero, scartando le esposizioni sotto `geosentinel.landslide.min-exposed-length-m` (default 20) e impostando `processed_at`. **Eccezione dichiarata** alla regola "una feature non usa le tabelle di un'altra": il join spaziale con `trails` deve stare nel database (ADR-0022). Solo lettura, nessun repository di `trail` usato
- [ ] **Rischio corrente di un sentiero:** il livello massimo tra l'ultima suscettibilità `DONE` e le propagazioni con segnalazione d'origine ancora `VERIFIED` (una frana `RESOLVED` smette di contare senza cancellare nulla)
- [ ] **Rotte** (`/api/v1/landslide-risk`, ogni `@Operation` con `operationId`, `*Response` con `REQUIRED`, regole di M7.1):
  | Metodo | Rotta | Chi | operationId |
  |---|---|---|---|
  | GET | `/trails?bbox=` | pubblica (informazione di sicurezza: la vede anche l'ospite) | `findTrailLandslideRisk` |
  | GET | `/trails/{trailId}` | pubblica | `getTrailLandslideRisk` |
  | GET | `/zones?bbox=&kind=` | pubblica, al massimo 500 poligoni | `findLandslideZones` |
  | POST | `/susceptibility-runs` | `ADMIN` | `requestSusceptibilityRun` |
  | POST | `/runout-runs` | `PARK_MANAGER` ("e se" su un punto) | `requestRunoutRun` |
  | GET | `/runs` (paginata, whitelist del sort) e `/runs/{id}` | `PARK_MANAGER` | `findSimulationRuns`, `getSimulationRun` |
  - le POST rispondono **202 Accepted** + `Location` del job (il calcolo è asincrono); al massimo 3 job `PENDING/RUNNING` per utente, oltre → 409 `simulation-queue-full` (nuovo slug in `ProblemTypes` + eccezione in `shared`)
  - le GET pubbliche entrano nel catalogo con ETag debole: vanno aggiunte **sia** in `HttpCacheConfig` **sia** in `PUBLIC_CATALOG` (regola di M7.3)
  - `TrailRiskResponse(trailId, level, exposedLengthM, exposedPath, sources, computedAt)`; `exposedPath` e i poligoni come GeoJSON: nuovi record `GeoJsonMultiLineString` e `GeoJsonMultiPolygon` in `shared/web/geo`, validati nel costruttore come `GeoJsonLineString`, + metodi in `GeometryMapper`
- [ ] **Push facoltativa** ai `PARK_MANAGER` quando un sentiero passa a `HIGH` per una propagazione: tipo `LANDSLIDE_RISK` + `trailId` nel payload (regole di M7.4: niente altro). L'app deve già tollerare tipi sconosciuti
- [ ] **Pulizia:** zone e rischi delle suscettibilità superate da più di 30 giorni cancellati da un job notturno
- [ ] `docs/openapi.json` rigenerato, `api-guide.md` con l'avviso "stima indicativa" e il ciclo di vita dei job, `CHANGELOG.md`

**Checkpoint (IT, senza il C++: le zone si inseriscono in SQL):**
- [ ] `LandslideJobIT`: verifica di una `LANDSLIDE` → un job `RUNOUT` `PENDING`; verifica di un altro tipo → nessun job; rollback della verifica → nessun job
- [ ] `LandslideIngestIT`: zona finta che taglia un sentiero del seed per 300 m → `exposed_length_m` 300 ± 5; segnalazione d'origine `RESOLVED` → il sentiero non risulta più a rischio; ingestione ripetuta → nessuna riga doppia
- [ ] `LandslideRiskApiIT`: GET pubblica da ospite → 200, seconda GET con `If-None-Match` → 304; POST da `HIKER` → 403; quarto job dello stesso utente → 409 `simulation-queue-full`; 202 con `Location`
- [ ] `MigrationIT` esteso: il ruolo `geosentinel_sim` non può leggere `users`
- [ ] `./mvnw verify` verde

#### M8.8 — Validazione, prestazioni, review
- [ ] **Validazione** su IFFI: curva ROC della mappa `P` contro le frane censite in Calabria, AUC calcolata e scritta in `docs/landslide-validation.md` con i parametri usati. Soglia minima: meglio del caso (AUC > 0,5); l'obiettivo è documentare, non dichiarare un modello certificato. Calibrazione degli intervalli di `soil-params.json` se l'AUC migliora
- [ ] **Prestazioni** in `docs/landslide-performance.md`, sul PC a 12 core:
  - strong scaling della suscettibilità e della propagazione: (P × T) = 1×1, 2×1, 4×1, 8×1, 12×1, 1×12, 2×6, 3×4
  - speedup ed efficienza, frazione seriale stimata (Amdahl) e quanto pesano I/O, `Gatherv` e poligonizzazione
  - confronto MPI puro contro ibrido a parità di core, con una spiegazione del risultato
- [ ] Facoltativo: accumulo D8 parallelo (M8.3) e rettangolo attivo della propagazione (M8.5), solo se le misure dicono che servono
- [ ] Review con la catena del §5 (riga 8)

**Checkpoint M8:**
- [ ] flusso completo dal vivo: segnalazione `LANDSLIDE` dall'app → verifica del gestore → job `DONE` → sentiero con rischio `HIGH` nella risposta di `/landslide-risk/trails`
- [ ] `ctest` e `./mvnw verify` verdi, job `simulation` verde in CI
- [ ] nessun file di dati (DEM, GeoTIFF) tracciato da git

**Fuori da M8:** pioggia prevista da un servizio meteo (lo scenario resta un parametro); mappa geologica e parametri del suolo per classe; lo strato di rischio nell'app (milestone da aggiungere al piano mobile quando le rotte sono stabili).

---

## 4. Ordine di esecuzione e dipendenze

> Ordine pianificato all'inizio. Quello reale è stato: feature (M1.3, M2, M3, M4, M5.2) prima dell'infrastruttura, poi M0.2 → M1.1 → M0.4 → M0.5 → M6 → verifiche dal vivo di M3/M4 → M5.3. Lezione: con `ddl-auto=validate` l'app non parte finché non esistono tutte le migrazioni, quindi M0.2 + M1.1 vanno fatti presto.

```
M0.1 → M0.2 → M0.3 → M0.4 → M0.5
  → M1.1 (V1–V7 insieme; richiede M0.2 o MigrationIT) → M1.2 → M1.3
  → M2.1 → M2.2 → M2.3 → M2.4 → M2.5 → M2.6 → M2.7
  → M5.1 (anticipato: da qui in poi ogni errore è già RFC 7807)
  → M3 → M4.1 → M4.2 → M4.3 → M4.4
  → M5.2 → M5.3
  → M6
  → M7
  → M8.1 → M8.2 → M8.3 → M8.4 → M8.5 → M8.6 → M8.8
           └─ M8.7 (Java, in parallelo dopo V14: si prova con zone finte)
```
Nota: `M5.1` (GlobalExceptionHandler) conviene farlo subito dopo M2, così ogni feature successiva nasce con gli errori già nel formato giusto. `AbstractIntegrationTest` (M6.1) puoi anticiparlo dopo M1 e scrivere gli IT insieme a ogni milestone. È la scelta consigliata.

---

## 5. Review dopo ogni milestone (`/ecc:orchestrate`)

Le catene di **implementazione** (`tdd-guide`, …) scriverebbero il codice al posto tuo, contro la regola del progetto. Qui ci sono solo catene di **review**: le lanci quando hai finito una milestone.

| # | Milestone | Chain |
|---|---|---|
| 0 | Infra, pom & CI | `ecc:java-build-resolver,ecc:code-reviewer` |
| 1 | Migrazioni & entità | `ecc:database-reviewer,ecc:java-reviewer` |
| 2 | Sicurezza | `ecc:security-reviewer,ecc:java-reviewer` |
| 3 | Object storage | `ecc:security-reviewer,ecc:java-reviewer` |
| 4 | Service & concorrenza | `ecc:database-reviewer,ecc:java-reviewer` |
| 5 | API & errori | `ecc:java-reviewer,ecc:code-reviewer` |
| 6 | Test | `ecc:java-reviewer,ecc:code-reviewer` |
| 7 | App mobile (KMP) | `ecc:security-reviewer,ecc:java-reviewer` |
| 8 | Rischio frane (C++ MPI/pthread) | `ecc:cpp-reviewer,ecc:database-reviewer,ecc:java-reviewer,ecc:security-reviewer` |

```bash
/ecc:orchestrate custom "ecc:java-build-resolver,ecc:code-reviewer" "[Plan: docs/MASTER_PLAN.md#step-0] Review only, do not write feature code: pom.xml deps for Spring Boot 4.1 (flyway-database-postgresql, oauth2-resource-server, mapstruct processor paths, springdoc 3, testcontainers 2), docker-compose healthchecks + minio-init bucket, Dockerfile multi-stage layers; Acceptance: mvnw compile green; all compose services healthy; no secrets in repo"
/ecc:orchestrate custom "ecc:database-reviewer,ecc:java-reviewer" "[Plan: docs/MASTER_PLAN.md#step-1] Review only: Flyway V1-V7 (PostGIS, geometry types with SRID 4326, GiST on column and on ::geography expression, FK indexes, CHECK enums, generated length_m) and JPA entities (JTS mapping lon/lat order, auditing, @Version, LAZY relations, equals/hashCode); Acceptance: ddl-auto=validate passes; every FK indexed; ST_DWithin plan uses geography GiST index"
/ecc:orchestrate custom "ecc:security-reviewer,ecc:java-reviewer" "[Plan: docs/MASTER_PLAN.md#step-2] Review only: stateless SecurityFilterChain with oauth2 resource server HS256, JwtEncoder claims, JTI Redis blacklist validator, refresh token hashing + atomic rotation + family reuse detection, Lua token-bucket RateLimitFilter, problem+json 401/403, RoleHierarchy and @PreAuthorize; Acceptance: refresh reuse revokes family; logout blacklists jti; no user enumeration on login"
/ecc:orchestrate custom "ecc:security-reviewer,ecc:java-reviewer" "[Plan: docs/MASTER_PLAN.md#step-3] Review only: MinIO ObjectStorage port/adapter, presigned POST policy (content-length-range, Content-Type eq) and GET with public endpoint signing client, server-generated object keys, MIME whitelist, size limits, per-user quota with advisory lock, confirm step via statObject + magic bytes with quarantine+delete on mismatch, cleanup job for unconfirmed uploads, visibility reusing HazardReportService.getHazard; Acceptance: no bytes proxied through controllers; foreign media returns 404; oversize upload rejected by storage"
/ecc:orchestrate custom "ecc:database-reviewer,ecc:java-reviewer" "[Plan: docs/MASTER_PLAN.md#step-4] Review only: PostGIS native queries (ST_DWithin geography meters, ST_Contains envelope), refuge booking PESSIMISTIC_WRITE + per-night occupancy via generate_series, SOS lock ordering alert-then-team, @Version plus explicit expectedVersion check, transaction-aware typed Redis cache, dedicated async executor with AFTER_COMMIT listener; Acceptance: no overbooking path; no deadlock ordering; cache holds DTOs only"
/ecc:orchestrate custom "ecc:java-reviewer,ecc:code-reviewer" "[Plan: docs/MASTER_PLAN.md#step-5] Review only: thin REST controllers with record DTOs and JSR-380, MapStruct mappers with GeometryMapper, If-Match/ETag on maintenance PATCH, GlobalExceptionHandler RFC 7807 mapping incl. AccessDenied from @PreAuthorize and lock failures, OpenAPI bearer scheme; Acceptance: every error is application/problem+json; no entity exposed in responses; 201 with Location"
/ecc:orchestrate custom "ecc:java-reviewer,ecc:code-reviewer" "[Plan: docs/MASTER_PLAN.md#step-6] Review only: AbstractIntegrationTest singleton PostGIS+Redis Testcontainers with @ServiceConnection, truncate cleanup without @Transactional, concurrency ITs with CountDownLatch for bookings/SOS/optimistic lock, spatial IT with lon/lat regression, ProblemDetail contract IT; Acceptance: mvnw verify green from clean; concurrency tests pass 5/5 repeats; no in-memory DB"
/ecc:orchestrate custom "ecc:security-reviewer,ecc:java-reviewer" "[Plan: docs/MASTER_PLAN.md#step-7] Review only: stable OpenAPI contract for Kotlin client generation (explicit operationIds, required response fields, committed openapi.json), /users/me profile + account deletion with anonymization and cross-feature cleanup through services, CGNAT-friendly auth rate limit + per-account limit, gzip + conditional GET, FCM push via PushNotifier port with no PII in payload and retry through notified_at, mobile upload contract, prod profile behind TLS proxy with forward headers; Acceptance: generated client compiles without nullable-everything; deleted account cannot log in; push failure is retried; no secrets in repo"
/ecc:orchestrate custom "ecc:cpp-reviewer,ecc:database-reviewer,ecc:java-reviewer,ecc:security-reviewer" "[Plan: docs/MASTER_PLAN.md#step-8] Review only: C++20 landslide simulation with MPI row-strip decomposition and halo exchange (MPI_THREAD_FUNNELED), pthread pool with barriers, counter-based RNG for reproducibility, GDAL windowed I/O and polygonize in EPSG:32633, SINMAP-style Monte Carlo factor of safety, cellular-automaton runout with mass conservation, libpqxx worker with FOR UPDATE SKIP LOCKED claim, lease and transactional result write, least-privilege DB role; Java landslide feature with BEFORE_COMMIT job enqueue, scheduled ingest computing trail exposure via ST_Intersects and geography length, public cached GET endpoints, 202 job endpoints; Acceptance: identical output for any process/thread count; mass conserved; job executed once under concurrent workers; worker role cannot read users; no data files in git"
```

---

## 6. Come lavoriamo da qui
M0–M7 sono completate, compreso M7.2.2 (17/10); l'app KMP è a M9 in `../GeoSentinel-Mobile`. Prossimo lavoro del backend: M8. Per altre funzionalità:
1. Aggiungi la milestone o il task qui, con contratti e checkpoint.
2. Scrivi il codice.
3. Chiedimi "review <task>": leggo i file e ti restituisco problemi, rischi e correzioni, **senza riscriverti il codice**.
4. Spunta i checkpoint e passa al task successivo.
