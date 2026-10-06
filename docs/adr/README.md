# Architecture Decision Records

Decisioni architetturali nel formato di Michael Nygard (contesto, decisione, conseguenze). Un ADR accettato
non si modifica: se una decisione cambia, se ne scrive uno nuovo che lo sostituisce e si aggiorna lo stato
del vecchio (`Sostituito da ADR-XXXX`). Il modello è in [`template.md`](template.md).

| # | Decisione | Data | Stato |
|---|---|---|---|
| [0001](0001-spring-boot-4.md) | Spring Boot 4 invece di 3.x | 2026-09-21 | Accettato |
| [0002](0002-esagonale-pragmatico.md) | Architettura esagonale pragmatica, package per feature | 2026-09-21 | Accettato |
| [0003](0003-jwt-resource-server.md) | JWT HS256 con il resource server di Spring Security | 2026-09-21 | Accettato |
| [0004](0004-refresh-token-opaco.md) | Refresh token opaco con rotazione per famiglia | 2026-09-21 | Accettato |
| [0005](0005-rate-limit-lua.md) | Rate limit con token bucket su Redis (script Lua) | 2026-09-21 | Accettato |
| [0006](0006-geography-gist.md) | Geometrie 4326 con query in `geography` e indice GiST sull'espressione | 2026-09-21 | Accettato |
| [0007](0007-geojson-record-dto.md) | GeoJSON con record DTO e MapStruct, senza librerie Jackson-JTS | 2026-09-21 | Accettato |
| [0008](0008-niente-lombok.md) | Niente Lombok | 2026-09-21 | Accettato |
| [0009](0009-uuid-pk.md) | Chiavi primarie UUID | 2026-09-21 | Accettato |
| [0010](0010-ordine-lon-lat.md) | Ordine delle coordinate: longitudine, latitudine | 2026-09-21 | Accettato |
| [0011](0011-bcrypt-72-byte.md) | Password con BCrypt e limite di 72 byte UTF-8 | 2026-09-22 | Accettato |
| [0012](0012-idempotency-key.md) | Idempotenza delle POST critiche con `Idempotency-Key` | 2026-09-22 | Accettato |
| [0013](0013-client-unico-kmp.md) | Unico client: app Kotlin Multiplatform | 2026-09-28 | Accettato |
| [0014](0014-lock-pessimistico.md) | Lock pessimistico per la capacità dei rifugi e gli SOS | 2026-09-24 | Accettato |
| [0015](0015-presigned-post-policy.md) | Upload delle foto con presigned POST policy | 2026-09-28 | Accettato |
| [0016](0016-push-fcm-diretto.md) | Push con FCM ai token dei dispositivi, senza topic | 2026-09-30 | Accettato |
| [0017](0017-revoca-per-utente.md) | Revoca per utente degli access token | 2026-10-01 | Accettato |
| [0018](0018-errori-rfc7807.md) | Errori RFC 7807 con slug stabili | 2026-09-22 | Accettato |
| [0019](0019-periodo-di-grazia-refresh.md) | Periodo di grazia sul refresh token appena ruotato | 2026-10-02 | Accettato |
| [0020](0020-percorso-nei-sentieri-della-mappa.md) | Percorso semplificato nei sentieri della mappa e bbox per intersezione | 2026-10-04 | Accettato |
| [0021](0021-limiti-per-utente-sos-e-prenotazioni.md) | Limiti per utente sulle push SOS e sulle prenotazioni | 2026-10-06 | Accettato |
