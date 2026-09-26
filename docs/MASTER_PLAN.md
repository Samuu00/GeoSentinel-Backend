# GeoSentinel — Master Implementation Plan (Backend)

> Ruolo di Claude: mentore/architetto/reviewer. **Il codice lo scrivi tu.** Qui trovi solo struttura, contratti (firme), vincoli e checkpoint.
> Regola di avanzamento: un task si chiude solo quando **tutti** i suoi checkpoint passano. Poi chiedi la review (vedi §5).

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

**Da sistemare subito (stato attuale del repo):**
- ~~`application.properties` → rimuovi `hibernate.dialect=...PostgresSpatialDialect`~~ (fatto).
- ~~Password DB hardcoded~~ → fatto: credenziali da `.env` con le chiavi `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, le stesse lette dall'immagine Postgres. `POSTGRES_DB` contiene il **nome** del database, non l'URL JDBC: l'URL si costruisce in `application.properties`. Aggiungi `.env.example`.
- ~~Base package reale~~ → fatto: `it.unical.geosentinel`.

---

## 1. Struttura del progetto

```
GeoSentinel-Backend/
├── docker-compose.yml
├── Dockerfile
├── .env.example
├── pom.xml
├── docs/
│   └── MASTER_PLAN.md
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
    │   │   │   │       └── IdempotencyKeyReusedException.java  # 422 (D12)
    │   │   │   ├── config/
    │   │   │   │   ├── JpaAuditingConfig.java        # @EnableJpaAuditing + AuditorAware
    │   │   │   │   ├── GeometryConfig.java           # GeometryFactory(SRID 4326) + Clock
    │   │   │   │   ├── AsyncConfig.java              # ThreadPoolTaskExecutor dedicato
    │   │   │   │   ├── CacheConfig.java              # RedisCacheManager tipizzato
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
    │   │   │   ├── application/   AuthService, port/TokenBlacklist
    │   │   │   ├── infrastructure/
    │   │   │   │   ├── persistence/  UserRepository, RefreshTokenRepository
    │   │   │   │   ├── redis/        RedisTokenBlacklist, RedisRateLimiter
    │   │   │   │   └── security/     SecurityConfig, JwtTokenService, JtiBlacklistValidator,
    │   │   │   │                     RateLimitFilter, ProblemAuthenticationEntryPoint,
    │   │   │   │                     ProblemAccessDeniedHandler, JwtProperties, CurrentUser
    │   │   │   └── web/           AuthController, dto/*
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
    │   │   │   ├── application/   MaintenanceService
    │   │   │   ├── infrastructure/persistence/  MaintenanceTicketRepository
    │   │   │   └── web/           MaintenanceController, dto/*, MaintenanceMapper
    │   │   │
    │   │   ├── emergency/                            # SOS e squadre di soccorso
    │   │   │   ├── domain/        SosAlert, SosStatus, RescueTeam, TeamStatus, SosAlertRaisedEvent
    │   │   │   ├── application/   SosService
    │   │   │   ├── infrastructure/
    │   │   │   │   ├── persistence/  SosAlertRepository, RescueTeamRepository
    │   │   │   │   └── notification/ SosNotificationListener   # @Async + AFTER_COMMIT
    │   │   │   └── web/           SosController, RescueTeamController, dto/*, SosMapper
    │   │   │
    │   │   └── media/                                # metadati file + MinIO
    │   │       ├── domain/        MediaFile, MediaStatus, MediaPolicy
    │   │       ├── application/   MediaService, port/ObjectStorage
    │   │       ├── infrastructure/
    │   │       │   ├── persistence/  MediaFileRepository
    │   │       │   └── storage/      MinioObjectStorage, MinioConfig, MinioProperties
    │   │       └── web/           MediaController, dto/*
    │   │
    │   └── resources/
    │       ├── application.properties
    │       ├── application-local.properties
    │       ├── scripts/token_bucket.lua
    │       └── db/
    │           ├── migration/   V1__extensions.sql … V7__media.sql
    │           └── seed/        R__local_seed.sql   (solo profilo local)
    └── test/
        ├── java/it/unical/geosentinel/
        │   ├── support/AbstractIntegrationTest.java
        │   ├── identity/  AuthServiceTest, AuthServiceLogoutTest, RateLimitFilterTest, AuthFlowIT, RateLimitIT
        │   ├── trail/     TrailDetailCacheSerializationTest, TrailSpatialQueryIT
        │   ├── refuge/    BookingTest, RefugeBookingServiceTest, BookingControllerTest, RefugeBookingConcurrencyIT
        │   ├── maintenance/ MaintenanceOptimisticLockIT
        │   ├── emergency/ SosAssignmentConcurrencyIT, SosAlertTest
        │   ├── media/     MediaServiceTest (+ MediaUploadIT opzionale)
        │   └── shared/    ProblemDetailWriterTest, GeoJsonLineStringTest, MaxUtf8BytesTest,
        │                  ProblemDetailContractIT, MigrationIT
        └── resources/application-test.properties
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
- [ ] `io.minio:minio` (versione esplicita, ultima stabile) → **rimandato a M3**
- [x] `org.mapstruct:mapstruct` + `mapstruct-processor` in `maven-compiler-plugin > annotationProcessorPaths`, con `-Amapstruct.defaultComponentModel=spring` e `-Amapstruct.unmappedTargetPolicy=ERROR`
- [x] `org.springdoc:springdoc-openapi-starter-webmvc-ui` (linea 3.x, per Boot 4)
- [x] Test: `spring-boot-testcontainers`, Testcontainers `postgresql` + `junit-jupiter` (Testcontainers 2: artefatti `testcontainers-postgresql`, `testcontainers-junit-jupiter`; verifica sul BOM)
- [x] Plugin `maven-failsafe-plugin` per i test `*IT` separati dagli unit test (`*Test` con surefire)
- [ ] `io.micrometer:micrometer-tracing-bridge-otel`: mette `traceId`/`spanId` nell'MDC per ogni richiesta, senza filtri scritti a mano (serve al `traceId` dei ProblemDetail in M5.1)

**Checkpoint:**
- [ ] `./mvnw -q dependency:tree` senza conflitti evidenti
- [x] `./mvnw -q compile` verde
- [ ] `unmappedTargetPolicy=ERROR` attivo (lo verificherai al primo mapper con un campo mancante)

#### M0.2 — `docker-compose.yml` + `.env.example`
**Obiettivo:** `docker compose up -d` porta su tutto, healthy, bucket già creato.

Servizi:
- [ ] `postgres`: `postgis/postgis:16-3.4`, `env_file: .env` (le chiavi `POSTGRES_*` sono le stesse lette dall'app), volume nominato, healthcheck `pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB`
- [ ] `redis`: `redis:7-alpine`, `--requirepass ${REDIS_PASSWORD}`, healthcheck `redis-cli -a ... ping`
- [ ] `minio`: immagine con **tag `RELEASE.*` pinnato** (mai `latest`; l'immagine community MinIO non riceve più rilasci regolari, pinna una release nota). `server /data --console-address :9001`, healthcheck `mc ready local`
- [ ] `minio-init`: `minio/mc`, `depends_on: minio: condition: service_healthy`, crea l'alias, fa `mb --ignore-existing` del bucket `geosentinel-media` e `anonymous set none` (bucket privato)
- [ ] `.env.example` con tutte le chiavi, valori finti: `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `MINIO_ROOT_USER/PASSWORD`, `MINIO_BUCKET`, `JWT_SECRET` (≥ 32 byte, base64). Le chiavi si aggiungono quando nasce il servizio che le usa.

**Vincoli:** nessun servizio `app` obbligatorio nel compose di sviluppo (l'app gira dall'IDE); se lo aggiungi, `depends_on` con `condition: service_healthy`.

**Checkpoint:**
- [ ] `docker compose ps` → tutti `healthy`, `minio-init` `exited (0)`
- [ ] `docker compose exec postgres psql -U ... -c "SELECT postgis_full_version();"` risponde
- [ ] Console MinIO su `:9001` mostra il bucket privato

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
- [ ] `application-local.properties`: `spring.flyway.locations=classpath:db/migration,classpath:db/seed`, springdoc attivo, logging SQL solo qui
- [x] Record di configurazione `@ConfigurationProperties`: `JwtProperties(secret, accessTtl, issuer)` (usato solo in `infrastructure/security`)
- [ ] `MinioProperties(endpoint, publicEndpoint, accessKey, secretKey, bucket, uploadUrlTtl, downloadUrlTtl)`, validato con `@Validated` (M3)
- `refresh-ttl` e `session-max-ttl` **non** stanno in `JwtProperties`: li usa `AuthService`, che sta in `application` e non può dipendere da `infrastructure`, quindi li legge con `@Value`.

**Checkpoint:**
- [ ] L'app parte col profilo `local` (fallirà su Flyway finché non c'è V1: è atteso)
- [ ] `grep -ri password src/main/resources` → solo placeholder `${...}`

#### M0.4 — `Dockerfile` multi-stage
- [ ] Stage `build`: `eclipse-temurin:21-jdk`, copia prima `pom.xml` + `.mvn` e fai `dependency:go-offline` (layer cache), poi `src` e `package -DskipTests`
- [ ] Stage `extract`: `java -Djarmode=tools -jar app.jar extract --layers --launcher`
- [ ] Stage `runtime`: `eclipse-temurin:21-jre`, utente non-root, copia i layer in ordine (dependencies → spring-boot-loader → snapshot-dependencies → application), `-XX:MaxRAMPercentage=75`

**Checkpoint:** `docker build .` verde; una modifica solo a `src` non invalida il layer delle dipendenze.

#### M0.5 — CI e supply chain
**Obiettivo:** ogni push verifica build, test e dipendenze senza intervento manuale.
- [ ] `.github/workflows/ci.yml`: JDK 21 (Temurin) con cache Maven, `./mvnw -B verify` (i runner GitHub hanno Docker, quindi girano anche gli IT con Testcontainers)
- [ ] `.github/dependabot.yml`: ecosistemi `maven`, `docker`, `github-actions`, cadenza settimanale
- [ ] Secret scanning + push protection attivi nelle impostazioni del repo (niente codice)

**Checkpoint:** una PR con un test rotto diventa rossa; Dependabot apre la prima PR.

---

### Milestone 1 — Migrazioni Flyway & entità di persistenza

#### M1.1 — DDL (`src/main/resources/db/migration`)
**Vincoli:** migrazioni **immutabili** una volta committate (errore = nuova `V` successiva). Ogni FK ha il suo indice. Enum = `varchar` + `CHECK`. Geometrie sempre con tipo e SRID espliciti.

| File | Contenuto |
|---|---|
| `V1__extensions.sql` | `CREATE EXTENSION IF NOT EXISTS postgis;` |
| `V2__identity.sql` | `users` (id uuid PK, email `varchar(254)`, password_hash, display_name `varchar(100)` (lunghezze allineate a `RegisterRequest` e alle `@Column(length)` di `User`: `validate` non controlla le lunghezze), role CHECK in 4 ruoli, enabled, audit cols, version). **`UNIQUE (email)` + `CHECK (email = lower(email))`**: il service salva e cerca l'email già normalizzata, quindi `WHERE email = ?` usa l'indice unico; il CHECK impedisce righe non normalizzate (es. dal seed). `refresh_tokens` (id, user_id FK, token_hash `varchar(64)` UNIQUE (non `char`: con `ddl-auto=validate` Hibernate si aspetta `varchar` per un `String` e rifiuta `bpchar`), family_id uuid, expires_at, **`family_expires_at`** (scadenza assoluta della sessione, copiata a ogni rotazione), revoked_at NULL, created_at); indici su `user_id`, `family_id`, `family_expires_at` (per la pulizia) |
| `V3__trails.sql` | `trails` (id, name `varchar(150)`, description, difficulty CHECK, status CHECK `OPEN/CLOSED/WARNING`, `path geometry(LineString,4326) NOT NULL`, **`length_m double precision GENERATED ALWAYS AS (ST_Length(path::geography)) STORED`**, audit, version). `pois` (id, trail_id FK NULL, name `varchar(100)`, type CHECK, `location geometry(Point,4326) NOT NULL`, **audit cols**: `Poi` estende `AuditableEntity`, senza `created_at/by` e `updated_at/by` la validazione fallisce all'avvio). `trails.description` è `text`. Le lunghezze di `name` sono allineate ai DTO e alle `@Column(length)` di `Trail`/`Poi` (`validate` non le controlla). `hazard_reports` (id, reporter_id FK, trail_id FK NULL, type, severity, description, `location geometry(Point,4326)`, status, audit) |
| `V4__refuges.sql` | `refuges` (id, name `varchar(120)`, `location geometry(Point,4326)`, capacity int `CHECK (capacity > 0)`, manager_id FK, audit). `bookings` (id, refuge_id FK, user_id FK, check_in date, check_out date, guests int `CHECK (guests > 0)`, status CHECK, `CHECK (check_out > check_in)`, `idempotency_key uuid NOT NULL`, audit); indice `(refuge_id, check_in, check_out)`; **`UNIQUE (user_id, idempotency_key)`** (D12) |
| `V5__maintenance.sql` | `maintenance_tickets` (id, trail_id FK, title, description, priority, status, assignee_id FK NULL, **`version bigint NOT NULL DEFAULT 0`**, audit); indice `(trail_id, status)` |
| `V6__emergency.sql` | `rescue_teams` (id, name, status CHECK `AVAILABLE/BUSY`, `base_location geometry(Point,4326)`). `sos_alerts` (id, hiker_id FK, `location geometry(Point,4326)`, location_updated_at, message, status CHECK `OPEN/IN_CHARGE/TEAM_DISPATCHED/RESOLVED/CANCELLED`, operator_id FK NULL, rescue_team_id FK NULL, `idempotency_key uuid NULL`, `notified_at timestamptz NULL`, created_at, taken_at, resolved_at). **Indice parziale** su `(created_at) WHERE status IN ('OPEN','IN_CHARGE','TEAM_DISPATCHED')` per la dashboard; **`UNIQUE (hiker_id, idempotency_key)`** (D12; i `NULL` non collidono); indice parziale `(created_at) WHERE notified_at IS NULL` per il recupero delle notifiche (M4.3) |
| `V7__media.sql` | `media_files` (id, owner_id FK, object_key UNIQUE, content_type, declared_size, actual_size NULL, status CHECK `PENDING/READY/REJECTED`, hazard_report_id FK NULL, trail_id FK NULL, `CHECK (num_nonnulls(hazard_report_id, trail_id) <= 1)`, created_at); indice `(owner_id, status)` |

**Indici spaziali (in ciascuna migrazione, accanto alla tabella):**
- `USING GIST (path)` / `USING GIST (location)`: per `ST_Contains`, `ST_Intersects`, bbox `&&`
- `USING GIST ((path::geography))` / `USING GIST ((location::geography))`: per `ST_DWithin` in metri (decisione D6)

- [ ] `db/seed/R__local_seed.sql` (repeatable, **solo profilo local**): un utente per ruolo (hash BCrypt precalcolato), 2–3 sentieri reali in Dolomiti, 1 rifugio con capacity piccola, 2 squadre

**Checkpoint:**
- [ ] App avviata: `flyway_schema_history` contiene V1–V7 con `success = true`
- [ ] `EXPLAIN` di una query `ST_DWithin(path::geography, …)` su `trails` mostra `Index Scan using …geography…` (con `SET enable_seqscan = off` se la tabella è piccola)
- [ ] `SELECT length_m FROM trails` restituisce metri plausibili per il seed

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
- [ ] `MaintenanceTicket`: `@Version Long version`; `void update(String title, String description, TicketPriority p, TicketStatus s, UUID assigneeId)`
- [ ] `SosAlert`: `void takeCharge(UUID operatorId, Instant now)` (solo da `OPEN`); `void dispatch(RescueTeam team)` (solo da `IN_CHARGE`); `void resolve(Instant now)`; `void cancelByHiker(Instant now)`; `void updateLocation(Point p, Instant now)` (solo se attivo)
- [ ] `RescueTeam`: `void markBusy()` (fallisce se già BUSY), `void markAvailable()`
- [ ] `User`, `RefreshToken`, `MediaFile`: vedi M2/M3

**Vincoli JTS:** ogni `Point`/`LineString` creato **solo** dal `GeometryFactory` bean (SRID corretto). Mai `new Coordinate(lat, lon)`: è `new Coordinate(lon, lat)`.

**Checkpoint M1:**
- [ ] L'app parte con `ddl-auto=validate` → nessun mismatch colonna/tipo
- [ ] Unit test `SosAlertTest`: transizioni legali OK, illegali lanciano (nessun Spring, JUnit puro)

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
- [ ] curl: register → login → chiamata protetta 200 → logout → stessa chiamata 401 problem+json
- [ ] refresh due volte con lo **stesso** token: la seconda → 401 e **anche** il token nuovo della prima rotazione è revocato
- [ ] refresh oltre `familyExpiresAt` → 401 anche con un token mai usato
- [ ] registrazione con password di 80 caratteri ASCII → 400 (non 500)
- [ ] access token scaduto → 401 (usa `accessTtl` di pochi secondi nel test)
- [ ] 6 login rapidi dallo stesso IP → il 6° riceve 429 con `Retry-After`
- [ ] HIKER su una rotta `PARK_MANAGER` → 403 problem+json

---

### Milestone 3 — Object storage (MinIO, pre-signed URL)

**Flusso (nessun byte passa dal backend):**
`POST /media/upload-requests` → validazione → riga `PENDING` + URL PUT firmato → il client carica su MinIO → `POST /media/{id}/confirm` → il backend fa `statObject`, verifica dimensione e tipo → `READY` (oppure `REJECTED` + delete dell'oggetto) → `GET /media/{id}/download-url` → URL GET firmato.

#### M3.1 — Porta ObjectStorage + adapter MinIO
- [ ] Porta `media.application.port.ObjectStorage`:
  - `URI presignedUpload(String objectKey, String contentType, Duration ttl)`
  - `URI presignedDownload(String objectKey, Duration ttl)`
  - `Optional<StoredObjectInfo> stat(String objectKey)` → record `(long size, String contentType)`
  - `void delete(String objectKey)`
- [ ] `MinioConfig`: **due** `MinioClient`: uno per le operazioni server-side (`endpoint` interno) e uno **solo per firmare** con `publicEndpoint` (la firma include l'host: un URL firmato con l'host interno Docker non funziona dal client)
- [ ] `MinioObjectStorage implements ObjectStorage`: `getPresignedObjectUrl(Method.PUT / GET)`; traduce `MinioException` in un'eccezione applicativa `StorageUnavailableException` (→ 503)

#### M3.2 — Policy e metadati
- [ ] `MediaPolicy` (valore di dominio, puro): whitelist `image/jpeg, image/png, image/webp → max 10 MB`, `video/mp4 → max 200 MB`; quota utente **1 GB**; `void check(String contentType, long declaredSize, long usedBytes)` lancia `UnsupportedMediaTypeException` (→ 415), `PayloadTooLargeException` (→ 413), `QuotaExceededException` (→ 422)
- [ ] `MediaFile` entity: `static MediaFile pending(UUID ownerId, String key, String contentType, long declaredSize, …)`; `void markReady(long actualSize)`; `void reject()`
- [ ] `MediaFileRepository`: `long sumSizeByOwnerAndStatusIn(UUID ownerId, Collection<MediaStatus> statuses)` (`COALESCE(SUM(...), 0)`)

#### M3.3 — `MediaService`
- [ ] `UploadTicket requestUpload(UUID ownerId, UploadRequestCommand cmd)` → `(UUID mediaId, URI uploadUrl, Instant expiresAt, Map<String,String> requiredHeaders)` (il client deve inviare lo stesso `Content-Type`)
- [ ] `MediaView confirmUpload(UUID ownerId, UUID mediaId)`: verifica ownership (404 se non tuo), `stat`, confronta size ≤ declared e content-type; mismatch → `reject` + `delete`
- [ ] `MediaView confirmUpload` verifica anche i **magic bytes**: legge i primi 16 byte dell'oggetto (`getObject` con range `0-15`) e controlla la firma (JPEG `FF D8 FF`, PNG `89 50 4E 47`, WebP `RIFF….WEBP`, MP4 `ftyp` all'offset 4). Il `Content-Type` restituito da `statObject` è quello dichiarato dal client, non una prova del contenuto: senza questo controllo un file HTML dichiarato `image/jpeg` passerebbe.
- [ ] `URI downloadUrl(UUID requesterId, UUID mediaId)`: solo `READY`. L'URL firmato forza gli header di risposta con i parametri di query `response-content-type` (il MIME validato) e `response-content-disposition=attachment` (nessun contenuto eseguito inline dal browser)
- **Vincoli:**
  - object key generata dal server: `{yyyy}/{MM}/{uuid}.{ext da MIME}`. **Mai** il filename del client (path traversal / sovrascrittura)
  - TTL: upload 10 min, download 5 min
  - la quota conta `PENDING + READY` (altrimenti la aggiri con upload paralleli non confermati)
- Rimandato: pulizia dei `PENDING` orfani (`@Scheduled` o lifecycle rule MinIO), da aggiungere quando ne vedi accumularsi. Hardening: presigned **POST policy** con `content-length-range`, se il confirm-step non ti basta.

**Checkpoint M3:**
- [ ] curl `upload-requests` → `curl -X PUT --upload-file foto.jpg -H "Content-Type: image/jpeg" "<url>"` → confirm → `READY`
- [ ] Upload di un file più grande del dichiarato → confirm → `REJECTED` e oggetto sparito dal bucket
- [ ] File di testo caricato come `image/jpeg` → confirm → `REJECTED`
- [ ] `application/x-msdownload` → 415 problem+json
- [ ] L'URL di download di un altro utente → 404

---

### Milestone 4 — Service di dominio & controllo della concorrenza

#### M4.1 — Sentieri, POI, segnalazioni (query PostGIS)
Query native (servono il cast `::geography` e le funzioni PostGIS):
- [x] `TrailRepository`:
  - `List<Trail> findWithinDistance(double lon, double lat, double radiusMeters, int limit)` → predicato `ST_DWithin(path::geography, ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography, :radius)`, ordinato per `ST_Distance` (stesse espressioni geography)
  - `List<Trail> findContainedIn(double minLon, double minLat, double maxLon, double maxLat)` → `ST_Contains(ST_MakeEnvelope(…, 4326), path)` (sentieri interamente nel viewport della mappa)
- [x] `PoiRepository`: `List<Poi> findNearby(double lon, double lat, double radiusMeters, @Nullable String type, int limit)`
  - **Trappola:** `:type IS NULL` con un `null` non tipizzato fa fallire PostgreSQL ("could not determine data type of parameter"): serve `CAST(:type AS varchar)`. Vale per ogni parametro facoltativo nelle query native
- [x] `HazardReportRepository`: `List<HazardReport> findActiveNearTrail(UUID trailId, double bufferMeters)` → `ST_DWithin(h.location::geography, t.path::geography, :buffer)` con join su `trails`
- [x] `TrailRepository.findContainedIn(…, int limit)`: anche la bbox ha un `LIMIT` (max 200), altrimenti un viewport grande quanto il mondo restituisce tutto
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
  - Limite noto: `updateCapacity` può scendere sotto le prenotazioni già confermate (serve per un rifugio danneggiato); le prenotazioni in eccesso vanno gestite a mano
  - Limite noto: `getUserBookings` (`GET /bookings/my`) restituisce una lista senza paginazione; passare a `Pageable` quando le prenotazioni per utente cresceranno
- **Perché per notte:** sommare tutte le prenotazioni che si sovrappongono al range sovrastima (due prenotazioni in notti diverse non si sommano).
- **Timeout:** `lock_timeout = 3s` (M0.3) → `PessimisticLockingFailureException` → 409 `resource-busy` + `Retry-After` (fatto in `GlobalExceptionHandler`).
- Limite noto: il lock sulla riga del rifugio serializza tutte le prenotazioni di quel rifugio. Va bene per decine di prenotazioni al secondo per rifugio; oltre quella soglia servirebbe una tabella `refuge_night_availability` con lock per notte.

#### M4.3 — Allerte SOS (Pessimistic Lock + evento asincrono)
- [ ] `SosAlertRepository`: `@Lock(PESSIMISTIC_WRITE) Optional<SosAlert> findByIdForUpdate(UUID id)`; `Page<SosAlert> findByStatusIn(Collection<SosStatus>, Pageable)`
- [ ] `RescueTeamRepository`: `@Lock(PESSIMISTIC_WRITE) Optional<RescueTeam> findByIdForUpdate(UUID id)`
- [ ] `SosService`:
  - `SosView raise(UUID hikerId, @Nullable UUID idempotencyKey, RaiseSosCommand)` (HIKER) → con una chiave già vista restituisce l'allerta esistente (D12), altrimenti crea e pubblica `SosAlertRaisedEvent(alertId, lon, lat, createdAt)` via `ApplicationEventPublisher`. La chiave qui è **facoltativa**: un SOS senza chiave non si rifiuta mai.
  - `void updateLocation(UUID hikerId, UUID alertId, GeoPointDto p)` (solo il proprietario)
  - `SosView takeCharge(UUID operatorId, UUID alertId)` → lock alert → `alert.takeCharge(...)`; se non `OPEN` → `AlertAlreadyTakenException` (409)
  - `SosView dispatchTeam(UUID operatorId, UUID alertId, UUID teamId)` → **ordine dei lock fisso: prima alert, poi team** (così non ci sono deadlock) → `team.markBusy()` + `alert.dispatch(team)`
  - `SosView resolve(UUID operatorId, UUID alertId)` → libera il team
  - `Page<SosView> activeAlerts(Pageable)` (RESCUE_OPERATOR)
  - `@PreAuthorize("hasRole('RESCUE_OPERATOR')")` sulle operazioni dell'operatore
- [x] `AsyncConfig`: bean `ThreadPoolTaskExecutor sosNotificationExecutor` → core 4, max 8, queue 100, `CallerRunsPolicy` (un SOS non si scarta mai), prefix `sos-notify-`, `setWaitForTasksToCompleteOnShutdown(true)` + `awaitTerminationSeconds 30`, `TaskDecorator` che propaga l'MDC (correlation id)
- [ ] `SosNotificationListener`: `@Async("sosNotificationExecutor") @TransactionalEventListener(phase = AFTER_COMMIT) void on(SosAlertRaisedEvent e)` → per ora log strutturato + individuazione della squadra `AVAILABLE` più vicina (query `ST_Distance` su `rescue_teams`); a notifica riuscita imposta `notified_at` (`UPDATE … WHERE id = ? AND notified_at IS NULL`)
- [ ] **Recupero delle notifiche perse** (`SosNotificationRecovery`): l'evento vive in memoria, quindi se l'app si ferma tra il commit e la notifica, l'SOS non viene notificato. `@Scheduled(fixedDelay = 30s)` rilancia la notifica per le allerte attive con `notified_at IS NULL` e `created_at < now - 60s`. La notifica deve quindi essere **idempotente** (può partire due volte). È la versione minima del pattern *transactional outbox*: una tabella outbox dedicata serve solo quando gli eventi da garantire diventano più di uno.
- **Vincoli:** `@Async` funziona solo se la chiamata passa dal proxy (non da `this.metodo()`); l'evento trasporta **dati**, non entità JPA (il listener gira fuori dalla transazione).
- **Dati personali:** la posizione di un escursionista è un dato personale (GDPR). Politica di conservazione: dopo 90 giorni dalla chiusura (`RESOLVED`/`CANCELLED`) la posizione si azzera o si arrotonda (job `@Scheduled`); decidi il valore e scrivilo nel codice.
- In alternativa al lock pessimistico basterebbe un `UPDATE … WHERE id=? AND status='OPEN'` condizionale. Il lock resta perché lo richiedi nelle specifiche e perché `dispatchTeam` tocca due righe.

#### M4.4 — Ticket di manutenzione (Optimistic Lock + cache)
- [ ] `MaintenanceTicketRepository`: `List<MaintenanceTicket> findByTrailIdOrderByCreatedAtDesc(UUID trailId)`
- [ ] `MaintenanceService`:
  - `TicketView open(UUID trailId, OpenTicketCommand)` → `@CacheEvict(value = "maintenance-by-trail", key = "#trailId")`
  - `List<TicketView> byTrail(UUID trailId)` → `@Cacheable("maintenance-by-trail")`
  - `TicketView update(UUID ticketId, long expectedVersion, UpdateTicketCommand)`:
    1. carica il ticket
    2. **`if (expectedVersion != ticket.getVersion()) throw new OptimisticConflictException(...)`**: JPA usa la versione dell'entità caricata, non quella del client; senza questo check il lost update tra GET e PATCH del client non viene rilevato
    3. `ticket.update(...)`: `@Version` protegge la finestra load→commit
    4. evict della cache del trail del ticket
  - tutti `@PreAuthorize("hasRole('PARK_MANAGER')")`
- `CacheConfig`:
  - [x] `RedisCacheManager` con **`.transactionAware()`**: put/evict avvengono dopo il commit (altrimenti un evict prima del commit + una lettura concorrente rimette in cache il dato vecchio)
  - [x] helper `CacheConfig.typed(...)` per un serializer JSON **tipizzato per cache**, senza *default typing* globale (rischio deserialization gadget); `disableCreateOnMissingCache()`: una cache non registrata fallisce invece di usare il serializer JDK
  - registrare le cache con `typed(...)` quando nascono i DTO, con un `RedisCacheManagerBuilderCustomizer` **nella feature** (`<feature>/infrastructure/cache`); prefisso chiavi `gs:cache:`:
    - [x] `trails` → `TrailDetail` (10 min), in `TrailCacheConfig`. Test: `TrailDetailCacheSerializationTest` (round-trip con lo stesso serializer)
    - [ ] `maintenance-by-trail` → lista di `TicketView` (5 min)
- **Si cachano solo DTO record**, mai entità (proxy lazy + `@Version` non serializzabili in modo sensato).

**Checkpoint M4 (manuale, i test automatici arrivano in M6):**
- [ ] `findNearby` con un punto a 100 m dal sentiero seed → lo trova; a 5 km con raggio 1 km → no
- [ ] `redis-cli KEYS 'gs:cache:*'` dopo un GET trail → chiave presente; dopo update → sparita
- [ ] Log mostra `sos-notify-1` che gestisce l'evento **dopo** la risposta HTTP del `raise`
- [ ] Due `POST /sos` con la stessa `Idempotency-Key` → una sola allerta, stessa risposta
- [ ] Due `POST /bookings` con la stessa `Idempotency-Key` → 201 poi 200 con la stessa prenotazione, una sola riga; stessa chiave con date diverse → 422
- [ ] Allerta con `notified_at` azzerato a mano nel DB → entro un minuto viene rinotificata

---

### Milestone 5 — API layer & gestione errori

#### M5.1 — `GlobalExceptionHandler` (RFC 7807)
**Pattern:** `@RestControllerAdvice` che estende `ResponseEntityExceptionHandler` (gestisce già 400/405/415 di Spring MVC in formato `ProblemDetail`).
- [x] `ProblemTypes`: costanti `URI` (`https://geosentinel.dev/problems/<slug>`), una per categoria
- [ ] Ogni risposta: `type`, `title`, `status`, `detail`, `instance` (path) + properties `timestamp`, `traceId` (letto dall'MDC, popolato da Micrometer Tracing: vedi M0.1)

Stato: in `GlobalExceptionHandler` sono già gestite tutte le righe tranne `AlertAlreadyTakenException` (M4.3), le eccezioni `media-*` e `StorageUnavailableException` (M3), che nasceranno con le loro feature. Manca ancora il `traceId` (M0.1).

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
| `UnsupportedMediaTypeException` / `PayloadTooLargeException` / `QuotaExceededException` | 415 / 413 / 422 | `media-*` |
| `StorageUnavailableException` | 503 | `storage-unavailable` |
| `AccessDeniedException` / `AuthorizationDeniedException` (da `@PreAuthorize`) | 403 | `forbidden` |
| `Exception` (fallback) | 500 | `internal-error`, detail generico, log con `traceId`, **mai** stack trace |

**Trappola:** le eccezioni di `@PreAuthorize` sono lanciate dentro l'invocazione del controller e quindi **arrivano** all'advice. Senza un handler dedicato le prende il fallback `Exception` → 500 invece di 403.

#### M5.2 — Controller e DTO
**Pattern:** controller *thin*: `@Valid` → service → mapper → `ResponseEntity`. `201 Created` con header `Location`. Paginazione con `Pageable` + wrapper `PagedModel`.

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
| `GET /api/v1/bookings/my` · `GET /api/v1/bookings/{id}` | owner | 404 per una prenotazione altrui |
| `DELETE /api/v1/bookings/{id}` | owner | 204 |
| `PATCH /api/v1/refuges/{id}/capacity` | PARK_MANAGER **gestore del rifugio**, o ADMIN | capacità; un altro PARK_MANAGER → 403 |
| `POST /api/v1/trails/{id}/maintenance-tickets` | PARK_MANAGER | |
| `GET /api/v1/trails/{id}/maintenance-tickets` | PARK_MANAGER | cached |
| `PATCH /api/v1/maintenance-tickets/{id}` | PARK_MANAGER | **`If-Match: "<version>"` obbligatorio** → 428 se manca; risposta con `ETag` |
| `POST /api/v1/sos` | HIKER | 201; `Idempotency-Key` facoltativo (mai rifiutare un SOS); retry → 200 con l'allerta esistente |
| `PATCH /api/v1/sos/{id}/location` | owner | |
| `POST /api/v1/sos/{id}/cancel` | owner | |
| `GET /api/v1/sos?status=…` | RESCUE_OPERATOR | paginato |
| `POST /api/v1/sos/{id}/take-charge` | RESCUE_OPERATOR | 409 se già preso |
| `POST /api/v1/sos/{id}/dispatch` `{teamId}` | RESCUE_OPERATOR | |
| `POST /api/v1/sos/{id}/resolve` | RESCUE_OPERATOR | |
| `GET /api/v1/rescue-teams?status=AVAILABLE` | RESCUE_OPERATOR | |
| `POST /api/v1/media/upload-requests` | auth | |
| `POST /api/v1/media/{id}/confirm` | owner | |
| `GET /api/v1/media/{id}/download-url` | owner / PM / RESCUE | |

Mapping: i service restituiscono view (`from(entity, GeometryMapper)`, vedi M4.1); il web ha i suoi record di request e **response** e un mapper MapStruct per feature (`TrailMapper`, `RefugeMapper`, `MaintenanceMapper`, `SosMapper`, `uses = GeometryMapper.class`) per request → command e view → response. La response separata decide cosa esce dall'API: `HazardResponse` non espone `reporterId` (dato personale), `TrailDetailResponse` non espone `version` (va nell'`ETag` quando servirà).

Validazione dei parametri: niente `@Validated` sui controller. Spring MVC valida già da solo `@RequestParam`/`@PathVariable` annotati (`HandlerMethodValidationException`, gestita in `GlobalExceptionHandler`); con `@Validated` si aggiungerebbe un proxy AOP che valida una seconda volta.

#### M5.3 — OpenAPI
- [x] `OpenApiConfig`: `@SecurityScheme(name = "bearerAuth", type = HTTP, scheme = "bearer", bearerFormat = "JWT")`, info del progetto
- [ ] `@Tag` per controller, `@ApiResponse` per 400/401/403/404/409 con schema `ProblemDetail`
- [x] Docs disabilitate fuori da `local` (`springdoc.api-docs.enabled: false` nel profilo base)

**Checkpoint M5:**
- [ ] Swagger UI su `/swagger-ui.html`: login con "Authorize" e chiamata di una rotta protetta
- [ ] Body malformato → 400 con `errors[]`; lat=200 → 400
- [ ] PATCH ticket senza `If-Match` → 428; con versione vecchia → 409
- [x] `POST /bookings` senza `Idempotency-Key` → 428; non UUID → 400 (`BookingControllerTest`)
- [ ] **Nessuna** risposta di errore con `Content-Type` diverso da `application/problem+json`

---

### Milestone 6 — QA & integration testing

#### M6.1 — `AbstractIntegrationTest`
**Pattern:** *singleton containers*: container `static`, avviati una volta per JVM, condivisi da tutte le classi IT. Così il context cache di Spring resta valido e la suite resta veloce.
- [ ] `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("test")`
- [ ] `PostgreSQLContainer` con `DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")` + `@ServiceConnection`
- [ ] Redis: `GenericContainer("redis:7-alpine").withExposedPorts(6379)` + `@ServiceConnection(name = "redis")`
- [ ] Avvio nel blocco `static { … start(); }` (**non** `@Container`, che li fermerebbe a fine classe)
- [ ] Pulizia: `@AfterEach` con `TRUNCATE … CASCADE` delle tabelle di dominio via `JdbcTemplate` + `FLUSHALL` Redis. **Non** `@Transactional` sui test di concorrenza (i thread worker non vedrebbero i dati e il rollback maschererebbe i lock)
- [ ] Helper: `String obtainToken(Role role)`, `RestClient`/`MockMvcTester` preconfigurato
- [ ] `application-test.properties`: `accessTtl` corto, rate limit alto (tranne in `RateLimitIT`)

#### M6.2 — Suite (ognuna = un file)
| Test | Cosa dimostra | Criterio |
|---|---|---|
| `MigrationIT` | Flyway V1–V7 + `ddl-auto=validate` su vero PostGIS | context carica |
| `TrailSpatialQueryIT` | `ST_DWithin` in **metri**, `ST_Contains` bbox | punto a ~100 m trovato con r=200, non con r=50; **regressione lon/lat**: con coordinate invertite il test fallisce |
| `RefugeBookingConcurrencyIT` | nessun overbooking | capacity 10, **50 thread** × 1 posto stesse date, ognuno con la **sua** `Idempotency-Key` (con la stessa chiave e lo stesso utente sarebbe un retry, non una nuova prenotazione), `CountDownLatch` di partenza → **esattamente 10 successi, 40 × 409**, `maxNightlyOccupancy == 10` |
| `SosAssignmentConcurrencyIT` | presa in carico esclusiva | 10 operatori concorrenti su un alert → **1 successo, 9 × 409**; dispatch concorrente dello stesso team su 2 alert → 1 successo |
| `MaintenanceOptimisticLockIT` | lost update impedito | due update con la stessa `expectedVersion` → il secondo 409; versione finale = +1 |
| `AuthFlowIT` | ciclo token completo | register/login/refresh/reuse-detection/logout-blacklist/sessione assoluta scaduta/password > 72 byte → 400 (scenari del checkpoint M2) |
| `IdempotencyIT` | nessun duplicato sui retry | 10 thread con la stessa `Idempotency-Key` su `POST /sos` e su una prenotazione → **una sola riga** creata, **un** 201 e nove 200 con lo stesso id; stessa chiave con body diverso → 422 |
| `SosNotificationRecoveryIT` | nessun SOS senza notifica | allerta con `notified_at` NULL più vecchia di 60 s → il job la notifica; lanciato due volte → una sola notifica effettiva |
| `RateLimitIT` | 429 + `Retry-After` | N+1-esima richiesta bloccata |
| `ProblemDetailContractIT` | contratto errori | 400/401/403/404/409/429 → `application/problem+json` con `type/title/status/instance` |
| Unit: `AuthServiceTest`, `RefugeBookingServiceTest`, `MediaServiceTest`, `SosAlertTest` | logica pura con Mockito | nessun container. `AuthServiceTest` verifica che con email inesistente `passwordEncoder.matches` venga **comunque** chiamato (tempo costante); `MediaServiceTest` copre i magic bytes |

Pattern del test di concorrenza (da scrivere tu): `ExecutorService` a N thread, `CountDownLatch ready/start`, ogni task chiama il **service** (non il repository) e registra successo/eccezione in collezioni thread-safe; `invokeAll` + asserzioni sui conteggi. Esegui ogni test di concorrenza **almeno 5 volte** di fila (`@RepeatedTest(5)`): se passa solo a volte, hai una race.

Opzionali, da aggiungere quando servono: `MediaUploadIT` col modulo Testcontainers MinIO; un test ArchUnit che fa rispettare le regole di dipendenza del §1 (utile se il team cresce); JaCoCo.

**Checkpoint M6 (Definition of Done del backend):**
- [ ] `./mvnw verify` verde da zero con Docker attivo, senza servizi locali accesi
- [ ] Test di concorrenza verdi 5/5
- [ ] Nessun test usa H2 o database in memoria

---

## 4. Ordine di esecuzione e dipendenze

```
M0.1 → M0.2 → M0.3 → M0.4 → M0.5
  → M1.1 → M1.2 → M1.3
  → M2.1 → M2.2 → M2.3 → M2.4 → M2.5 → M2.6 → M2.7
  → M5.1 (anticipato: da qui in poi ogni errore è già RFC 7807)
  → M3 → M4.1 → M4.2 → M4.3 → M4.4
  → M5.2 → M5.3
  → M6
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

```bash
/ecc:orchestrate custom "ecc:java-build-resolver,ecc:code-reviewer" "[Plan: docs/MASTER_PLAN.md#step-0] Review only, do not write feature code: pom.xml deps for Spring Boot 4.1 (flyway-database-postgresql, oauth2-resource-server, mapstruct processor paths, springdoc 3, testcontainers 2), docker-compose healthchecks + minio-init bucket, Dockerfile multi-stage layers; Acceptance: mvnw compile green; all compose services healthy; no secrets in repo"
/ecc:orchestrate custom "ecc:database-reviewer,ecc:java-reviewer" "[Plan: docs/MASTER_PLAN.md#step-1] Review only: Flyway V1-V7 (PostGIS, geometry types with SRID 4326, GiST on column and on ::geography expression, FK indexes, CHECK enums, generated length_m) and JPA entities (JTS mapping lon/lat order, auditing, @Version, LAZY relations, equals/hashCode); Acceptance: ddl-auto=validate passes; every FK indexed; ST_DWithin plan uses geography GiST index"
/ecc:orchestrate custom "ecc:security-reviewer,ecc:java-reviewer" "[Plan: docs/MASTER_PLAN.md#step-2] Review only: stateless SecurityFilterChain with oauth2 resource server HS256, JwtEncoder claims, JTI Redis blacklist validator, refresh token hashing + atomic rotation + family reuse detection, Lua token-bucket RateLimitFilter, problem+json 401/403, RoleHierarchy and @PreAuthorize; Acceptance: refresh reuse revokes family; logout blacklists jti; no user enumeration on login"
/ecc:orchestrate custom "ecc:security-reviewer,ecc:java-reviewer" "[Plan: docs/MASTER_PLAN.md#step-3] Review only: MinIO ObjectStorage port/adapter, presigned PUT/GET with public endpoint signing client, server-generated object keys, MIME whitelist, size limits, per-user quota counting PENDING+READY, confirm step via statObject with reject+delete on mismatch; Acceptance: no bytes proxied through controllers; foreign media returns 404; oversize upload rejected"
/ecc:orchestrate custom "ecc:database-reviewer,ecc:java-reviewer" "[Plan: docs/MASTER_PLAN.md#step-4] Review only: PostGIS native queries (ST_DWithin geography meters, ST_Contains envelope), refuge booking PESSIMISTIC_WRITE + per-night occupancy via generate_series, SOS lock ordering alert-then-team, @Version plus explicit expectedVersion check, transaction-aware typed Redis cache, dedicated async executor with AFTER_COMMIT listener; Acceptance: no overbooking path; no deadlock ordering; cache holds DTOs only"
/ecc:orchestrate custom "ecc:java-reviewer,ecc:code-reviewer" "[Plan: docs/MASTER_PLAN.md#step-5] Review only: thin REST controllers with record DTOs and JSR-380, MapStruct mappers with GeometryMapper, If-Match/ETag on maintenance PATCH, GlobalExceptionHandler RFC 7807 mapping incl. AccessDenied from @PreAuthorize and lock failures, OpenAPI bearer scheme; Acceptance: every error is application/problem+json; no entity exposed in responses; 201 with Location"
/ecc:orchestrate custom "ecc:java-reviewer,ecc:code-reviewer" "[Plan: docs/MASTER_PLAN.md#step-6] Review only: AbstractIntegrationTest singleton PostGIS+Redis Testcontainers with @ServiceConnection, truncate cleanup without @Transactional, concurrency ITs with CountDownLatch for bookings/SOS/optimistic lock, spatial IT with lon/lat regression, ProblemDetail contract IT; Acceptance: mvnw verify green from clean; concurrency tests pass 5/5 repeats; no in-memory DB"
```

---

## 6. Come lavoriamo da qui
1. Scegli il task (parti da **M0.1**).
2. Scrivi il codice.
3. Chiedimi "review M0.1": leggo i file e ti restituisco problemi, rischi e correzioni, **senza riscriverti il codice**.
4. Spunta i checkpoint e passa al task successivo.
