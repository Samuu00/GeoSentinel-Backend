# GeoSentinel Backend

Backend per la sicurezza dei sentieri montani: sentieri e POI, segnalazioni di pericolo, rifugi e prenotazioni,
ticket di manutenzione, SOS con squadre di soccorso, foto, notifiche push. L'unico client è un'app
Kotlin Multiplatform (Android + iOS), che genera il suo client dal contratto [`docs/openapi.json`](docs/openapi.json).

Java 21, Spring Boot 4, PostgreSQL 16 + PostGIS, Redis, MinIO.

## Documentazione

- [Architettura](docs/architecture.md) (arc42 con diagrammi C4) e [ADR](docs/adr/README.md) con il perché delle scelte.
- [Guida all'API](docs/api-guide.md) e [contratto OpenAPI](docs/openapi.json), per chi sviluppa l'app.
- [Runbook](docs/operations.md): configurazione, avvio in locale, problemi già incontrati.
- [CONTRIBUTING](CONTRIBUTING.md), [SECURITY](SECURITY.md), [CHANGELOG](CHANGELOG.md).
- [Piano di lavoro](docs/MASTER_PLAN.md), con milestone e checkpoint.

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
e la firma include l'host, quindi nel `.env` va l'indirizzo del PC così come lo vede il dispositivo:

| Dispositivo | URL dell'API | `MINIO_PUBLIC_ENDPOINT` |
|---|---|---|
| Emulatore Android | `http://10.0.2.2:8080` | `http://10.0.2.2:9000` |
| Simulatore iOS | `http://localhost:8080` | `http://localhost:9000` |
| Telefono vero (stessa Wi-Fi) | `http://<IP del PC>:8080` | `http://<IP del PC>:9000` |

Per un telefono vero aprire le porte 8080 e 9000 nel firewall di Windows. HTTP in chiaro solo in sviluppo,
abilitato dall'app: Android con un `network_security_config` che permette `10.0.2.2` e l'IP del PC,
iOS con `NSAllowsLocalNetworking` in `Info.plist`.
