# GeoSentinel — Backend

Backend per la sicurezza dei sentieri montani: sentieri e POI, segnalazioni di pericolo, rifugi e prenotazioni,
ticket di manutenzione, SOS con squadre di soccorso, foto, notifiche push. L'unico client è un'app
Kotlin Multiplatform (Android + iOS), che genera il suo client dal contratto [`docs/openapi.json`](docs/openapi.json).

Java 21, Spring Boot 4, PostgreSQL 16 + PostGIS, Redis, MinIO.

## Documentazione

| | |
|---|---|
| [Architettura](docs/architecture.md) | arc42 + diagrammi C4 |
| [Decisioni (ADR)](docs/adr/README.md) | Perché il sistema è fatto così |
| [Guida all'API](docs/api-guide.md) · [OpenAPI](docs/openapi.json) | Per chi sviluppa l'app |
| [Runbook](docs/operations.md) | Configurazione, avvio, backup, problemi noti |
| [Contribuire](CONTRIBUTING.md) · [Sicurezza](SECURITY.md) · [Changelog](CHANGELOG.md) | |
| [Piano di lavoro](docs/MASTER_PLAN.md) | Milestone e checkpoint |

## Sviluppo

```bash
cp .env.example .env                 # poi sostituire i valori
docker compose up -d                 # PostGIS, Redis, MinIO (+ bucket privato)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
./mvnw verify                        # unit + integration test (Testcontainers, serve Docker)
```

Il profilo `local` carica i dati di prova e accende Swagger su `/swagger-ui.html`. Utenti di prova
(password `Password123!`): `admin@`, `manager@`, `rescuer@`, `hiker@`, `hiker2@geosentinel.dev`.

### Collegare l'app in sviluppo

L'app deve raggiungere sia l'API sia MinIO. Gli URL dei media sono firmati con `MINIO_PUBLIC_ENDPOINT`
(la firma include l'host), quindi nel `.env` va l'indirizzo con cui il **dispositivo** vede il PC:

| Dispositivo | URL dell'API | `MINIO_PUBLIC_ENDPOINT` |
|---|---|---|
| Emulatore Android | `http://10.0.2.2:8080` | `http://10.0.2.2:9000` |
| Simulatore iOS | `http://localhost:8080` | `http://localhost:9000` |
| Telefono vero (stessa Wi-Fi) | `http://<IP del PC>:8080` | `http://<IP del PC>:9000` |

Per un telefono vero aprire le porte 8080 e 9000 nel firewall di Windows. HTTP in chiaro solo in sviluppo,
abilitato dall'app: Android con un `network_security_config` che permette `10.0.2.2` e l'IP del PC,
iOS con `NSAllowsLocalNetworking` in `Info.plist`.

## Staging / produzione

iOS rifiuta HTTP fuori dalla rete locale (App Transport Security): per i tester serve HTTPS.
`docker-compose.prod.yml` avvia tutto dietro [Caddy](deploy/Caddyfile), che ottiene i certificati Let's Encrypt
da solo; dall'esterno sono raggiungibili solo le porte 80 e 443.

1. Due record DNS verso il server, ad esempio `api.example.org` e `media.example.org`.
2. Sul server, un file `.env.prod` (mai nel repository) con chiavi **diverse** da quelle di sviluppo:
   ```properties
   POSTGRES_DB=geosentinel
   POSTGRES_USER=geosentinel
   POSTGRES_PASSWORD=...        # openssl rand -hex 24
   REDIS_PASSWORD=...           # openssl rand -hex 24
   JWT_SECRET=...               # openssl rand -hex 32
   MINIO_ROOT_USER=geosentinel
   MINIO_ROOT_PASSWORD=...      # openssl rand -hex 24
   API_DOMAIN=api.example.org
   MEDIA_DOMAIN=media.example.org
   # Facoltative: BACKUP_DIR (default ./backups), BACKUP_KEEP_DAYS (14), PUSH_ENABLED + GOOGLE_APPLICATION_CREDENTIALS
   ```
3. `docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build`

L'app usa `https://api.example.org`; `GET /actuator/health` (pubblico, senza dettagli) serve al monitoraggio.

### Backup

`db-backup` salva ogni giorno un dump di Postgres in `BACKUP_DIR/db` (conservato `BACKUP_KEEP_DAYS` giorni),
`media-backup` una copia speculare del bucket in `BACKUP_DIR/media`. Restano sul server: copiarli altrove.
Ripristino del database:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod exec db-backup \
  pg_restore --clean --if-exists -d geosentinel /backups/db/geosentinel-AAAA-MM-GG.dump
```

### Prova in locale

Con `API_DOMAIN=api.localhost`, `MEDIA_DOMAIN=media.localhost`, `HTTP_PORT=8088`, `HTTPS_PORT=8443` e
`MINIO_PUBLIC_ENDPOINT=https://media.localhost:8443` lo stesso compose gira sul PC; Caddy usa la sua CA
locale (`/data/caddy/pki/authorities/local/root.crt` nel container) al posto di Let's Encrypt.
