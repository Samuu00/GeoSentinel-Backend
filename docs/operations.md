# Runbook

Come configurare, avviare, controllare e ripristinare il backend. Per l'architettura vedi
[architecture.md](architecture.md), per il deploy in produzione anche il [README](../README.md#staging--produzione).

## Requisiti

| Componente | Versione |
|---|---|
| JDK | 21 (`JAVA_HOME`; Maven usa il wrapper `./mvnw`) |
| Docker + Compose | Docker 29, Compose v2+ |
| PostgreSQL + PostGIS | `postgis/postgis:16-3.4-alpine` |
| Redis | `redis:7.2-alpine` |
| MinIO | `pgsty/minio` (fork della community, tag `RELEASE` bloccato) |

## Configurazione

Le variabili arrivano dal `.env` (sviluppo, importato da Spring con `spring.config.import`) o
dall'ambiente del container (produzione). Modello completo in [`.env.example`](../.env.example).

| Variabile | Obbligatoria | Default | Uso |
|---|---|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | sì | — | Database (le legge anche l'immagine Postgres) |
| `DB_HOST`, `DB_PORT` | no | `localhost`, `5432` | Indirizzo del database |
| `REDIS_PASSWORD` | sì con il compose | vuota | Redis con `--requirepass` |
| `REDIS_HOST`, `REDIS_PORT` | no | `localhost`, `6379` | Indirizzo di Redis |
| `JWT_SECRET` | sì | — | Firma HS256, almeno 32 byte (l'avvio fallisce se più corto) |
| `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` | sì | — | Credenziali MinIO (le legge anche l'immagine) |
| `MINIO_BUCKET` | no | `geosentinel-media` | Bucket privato delle foto |
| `MINIO_ENDPOINT` | no | `http://localhost:9000` | Indirizzo di MinIO visto dal backend |
| `MINIO_PUBLIC_ENDPOINT` | no | `http://localhost:9000` | Indirizzo di MinIO visto dal **dispositivo**: entra nella firma degli URL |
| `PUSH_ENABLED` | no | `false` | Accende FCM; altrimenti le push vanno solo nel log |
| `GOOGLE_APPLICATION_CREDENTIALS` | se `PUSH_ENABLED` | — | Percorso del JSON dell'account di servizio Firebase |

Solo per `docker-compose.prod.yml`: `API_DOMAIN`, `MEDIA_DOMAIN`, `HTTP_PORT`, `HTTPS_PORT`, `BACKUP_DIR`,
`BACKUP_KEEP_DAYS`.

Parametri applicativi principali (`application.properties`):

| Proprietà | Valore | Significato |
|---|---|---|
| `geosentinel.jwt.access-ttl` | `15m` | Durata dell'access token |
| `geosentinel.jwt.refresh-ttl` | `7d` | Durata di un refresh token |
| `geosentinel.jwt.session-max-ttl` | `30d` | Durata massima di una sessione |
| `geosentinel.rate-limit.login-per-minute` | `30` | Per IP su login, registrazione, logout |
| `geosentinel.rate-limit.refresh-per-minute` | `120` | Per IP sul refresh |
| `geosentinel.rate-limit.general-per-minute` | `60` | Per utente (o IP) sul resto |
| `geosentinel.registration.min-age` | `14` | Età minima per registrarsi |
| `geosentinel.scheduling.enabled` | `true` | Job programmati (spenti solo nei test) |
| `spring.data.web.pageable.max-page-size` | `100` | Pagina massima |

### Profili

| Profilo | Uso | Differenze |
|---|---|---|
| (nessuno) | Base | Swagger spento |
| `local` | Sviluppo | Seed di prova (`db/seed`), Swagger acceso, log SQL |
| `prod` | Dietro Caddy | `forward-headers-strategy=native`, Swagger spento, log `INFO` |
| `test` | Test di integrazione | Segreti finti, job programmati spenti |

## Avvio e arresto

```bash
docker compose up -d                                        # PostGIS, Redis, MinIO + bucket
./mvnw spring-boot:run -Dspring-boot.run.profiles=local     # backend
docker compose --profile app up -d --build                  # oppure backend in container
docker compose down                                         # ferma (i volumi restano)
```

All'avvio Flyway applica le migrazioni mancanti e Hibernate valida lo schema: se qualcosa non torna,
l'applicazione non parte. L'arresto è graceful (20 s per finire le richieste in corso).

## Salute e osservabilità

- `GET /actuator/health` → `{"status":"UP"}`: pubblico, senza dettagli; gli healthcheck di Docker lo usano.
- Ogni log ha il `traceId` (Micrometer Tracing); lo stesso `traceId` è in ogni risposta d'errore, quindi
  dal messaggio dell'utente si arriva alla riga di log.
- Chiavi Redis utili per capire un comportamento:

| Prefisso | Contenuto | TTL |
|---|---|---|
| `bl:jti:<jti>` | Access token revocato (logout) | fino alla scadenza del token |
| `bl:user:<id>` | Token dell'utente revocati fino a quel secondo | 15 min |
| `rl:auth:`, `rl:refresh:`, `rl:` | Bucket del rate limit | gestito dallo script |
| `rl:login:<sha256(email)>` | Tentativi di login per account | gestito dallo script |
| `gs:cache:trails::<id>`, `gs:cache:maintenance-by-trail::<id>` | Cache dei DTO | 10 min (sentieri), 5 min (ticket) |

## Lavori programmati

| Job | Frequenza | Effetto | Se fallisce |
|---|---|---|---|
| `SosNotificationRecovery` | ogni 30 s | Rinvia le notifiche SOS con `notified_at` NULL da oltre 60 s | Riprova al giro successivo |
| `MediaCleanupJob` | ogni 15 min | Cancella riga e oggetto degli upload non confermati (TTL + 1 h) | Riprova al giro successivo |
| `RefreshTokenCleanupJob` | 03:30 UTC | Cancella le famiglie di refresh token scadute | Nessun effetto per l'utente |
| `SosDataRetentionJob` | 04:00 UTC | Anonimizza gli SOS chiusi da più di 90 giorni | Riprova la notte dopo |

## Backup e ripristino (produzione)

`docker-compose.prod.yml` salva ogni giorno un dump di Postgres (`BACKUP_DIR/db`, conservato
`BACKUP_KEEP_DAYS` giorni) e una copia speculare del bucket (`BACKUP_DIR/media`).

```bash
# ripristino del database
docker compose -f docker-compose.prod.yml --env-file .env.prod exec db-backup \
  pg_restore --clean --if-exists -d geosentinel /backups/db/geosentinel-AAAA-MM-GG.dump

# ripristino delle foto
docker compose -f docker-compose.prod.yml --env-file .env.prod exec media-backup \
  mc mirror --overwrite /backups/media local/geosentinel-media
```

Il ripristino è stato provato l'01/10 su un database temporaneo. I backup restano sul server: per
sopravvivere a un guasto vanno copiati altrove.

## Problemi noti

| Sintomo | Causa | Rimedio |
|---|---|---|
| Postgres non parte o l'app si collega al database sbagliato | Porta 5432 già usata (es. PostgreSQL di Windows) | `DB_PORT=5433` nel `.env` (la leggono compose e app) |
| `Port 8080 was already in use` | Un'altra applicazione sulla 8080 | `--server.port=18080` |
| `package`/`verify` falliscono su Windows | Il jar è in uso da un'istanza accesa | Fermare l'istanza |
| Login e rate limit falliscono, `WRONGPASS` nei log | `REDIS_PASSWORD` diversa da quella del container | Allineare il `.env` e ricreare Redis |
| L'app non parte: `Validate failed: Migrations have failed validation` | Una migrazione già applicata è stata modificata | Mai modificare `V1`–`V9`: ripristinare il file e scrivere una `V10` |
| Upload dal telefono → 403 `SignatureDoesNotMatch` | `MINIO_PUBLIC_ENDPOINT` diverso dall'indirizzo usato dal dispositivo | Impostarlo come nel [README](../README.md#collegare-lapp-in-sviluppo) e riavviare |
| Upload → 400 `EmptyRequestBody` | Il client manda il multipart chunked | Inviare con `Content-Length` |
| Con `PUSH_ENABLED=true` l'avvio fallisce | File delle credenziali assente o illeggibile | Controllare `GOOGLE_APPLICATION_CREDENTIALS` |
| Le notifiche SOS non arrivano | Nessun dispositivo di un operatore registrato | Warning `No rescue operator device registered` nel log; registrare l'app di un operatore |
