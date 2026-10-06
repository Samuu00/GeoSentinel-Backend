# Audit di sicurezza del 05/10 — piste risolte il 06/10

Audit del commit `42e34bc`, svolto solo sul sorgente: non è stato eseguito nessun test. Il report completo è fuori dal repo, in `~/security-audit-skill/GeoSentinel-Backend/run-1/`, con `REPORT.md`, `NEEDS-VALIDATION.md` e `findings.json`.

Le 7 piste qui sotto **non sono confermate**. Per ognuna si scrive prima l'IT indicato: se fallisce come previsto, si corregge il codice e lo stesso test diventa il test di regressione; se passa, la pista si chiude.

Ordine di lavoro consigliato: 1, 2, 6, poi le altre.

| # | Pista | Dove | IT per verificarla | Correzione proposta |
|---|---|---|---|---|
| 1 | Una refresh concorrente al cambio password sopravvive alla revoca. Il bulk `revokeAllOfUser` in READ COMMITTED non vede il successore inserito da una rotazione non ancora committata. | `AuthService.refresh` (`:159`, `:227`), `AuthService.changePassword` (`:123`), `RefreshTokenRepository.revokeAllOfUser` | `identity/PasswordChangeRefreshRaceIT`: `runConcurrently(2, …)` più un ritardo di test in `createAuthSession`; dopo il cambio, la refresh col token nuovo deve dare 401 | Lock sulla riga dell'utente (`PESSIMISTIC_WRITE`) sia in `changePassword` sia in `refresh`; in alternativa `users.password_changed_at` (V11) e refresh rifiutata per le famiglie create prima |
| 2 | La policy POST presigned resta valida per 10 min anche dopo confirm, quarantena o delete: si può sostituire un oggetto già verificato, aggirare la quota, lasciare oggetti senza riga | `MinioObjectStorage.presignUpload` (`:68`), `MediaService.confirmUpload` (`:123`), `deleteMedia` (`:226`), `MediaCleanupJob` (`:44`) | `media/MediaPolicyReuseIT` (setup di `MediaUploadIT`): seconda POST con gli stessi `formFields` dopo READY, delete e QUARANTINED | Upload su una chiave di staging; il confirm copia l'oggetto verificato sulla chiave finale e cancella lo staging; pulizia dello staging per età (job o regola di lifecycle MinIO) |
| 3 | Il ciclo SOS raise → cancel → raise manda una push a tutti gli operatori a ogni giro, fuori dal rate limit (ADR-0005) | `RateLimitFilter` (`:48`), `SosService.raiseSos` (`:62`), `SosNotificationListener.deliver` (`:58`) | `notification/SosPushFanoutIT`, riusando `FakePushNotifier` di `SosPushIT`: 10 cicli, 10 push | L'allerta si crea sempre ("un SOS non si rifiuta mai"), ma la push agli operatori va limitata o accorpata per escursionista (es. una ogni N minuti) |
| 4 | Un solo HIKER può occupare tutti i posti di un rifugio: non c'è un limite per utente né un orizzonte, e nessuna API permette di liberarli | `RefugeBookingService.book` (`:88`), `validateBookingDates` (`:53`) | `refuge/BookingHoardingIT`: un account riempie il rifugio, un secondo account riceve 409 | Tetto per utente (prenotazioni attive o ospiti-notte) e orizzonte massimo del check-in, con 422 |
| 5 | Un check-in nel futuro lontano (es. anno 200000) fa girare un `generate_series` enorme in `updateCapacity`, sotto il lock del rifugio | `validateBookingDates` (`:53`), `updateCapacity` (`:184-196`), `BookingRepository` (`:21`, `:40`) | `refuge/BookingHorizonIT` + `EXPLAIN ANALYZE` | Orizzonte massimo del check-in (es. oggi + 365 giorni, 422) e `statement_timeout` nella `connection-init-sql` |
| 6 | Un token revocato torna valido fino a ~60 s dopo `exp`: le TTL di Redis non tengono conto dello skew di 60 s di `JwtTimestampValidator` | `RedisTokenBlacklist` (`:39`, `:50`), `AuthService.logout` (`:200`), `UserAccountService.deleteAccount` (`:92`), `SecurityConfig.jwtDecoder` (`:120`) | IT con un token che scade tra 5 s: logout, attesa oltre `exp`, `GET /users/me` deve dare 401 | TTL + 60 s su `bl:jti` e `bl:user`, e revoca del jti quando `exp > now - skew`; in alternativa skew esplicito a 0-5 s nel decoder |
| 7 | `GET /hazards/trail/{id}` non ha `LIMIT` né paginazione e mostra a tutti le SUBMITTED; qualsiasi HIKER può gonfiare la lista | `HazardReportRepository.findActiveNearTrail` (`:15`) contro `findActiveNearby` (`:35`) | `trail/HazardTrailListSizeIT` con 1k/10k/50k righe | `LIMIT`/paginazione + `ORDER BY`; decidere se le SUBMITTED vanno mostrate solo al segnalatore e al PARK_MANAGER, come su `/nearby` |

Note:
- Le correzioni 3, 4, 5 e 7 cambiano il comportamento delle API. Vanno quindi aggiornati `docs/openapi.json` (con `-Dopenapi.update=true`) e il `CHANGELOG.md`. Se nascono decisioni nuove (tetto per utente, push SOS accorpate), si scrive un ADR.
- La V11 serve solo se per la pista 1 si sceglie `password_changed_at`.
- Le note di hardening minori (credenziali root di MinIO in produzione, backend del compose di sviluppo esposto sulla LAN, 403 invece di 404, `LoginRequest.email` senza `@Size` e altre) sono nella sezione 6 del `REPORT.md` dell'audit.

## Esito (06/10)

Tutte e 7 le piste erano reali: senza la correzione, ogni test qui sotto fallisce; con la correzione passa (`./mvnw clean verify` verde).

| # | Test di regressione | Correzione |
|---|---|---|
| 1 | `identity/PasswordChangeRefreshRaceIT` (senza fix: refresh col token ruotato → 200) | `UserRepository.findByIdForUpdate` in `changePassword`, `deleteAccount` e `refresh` (prima l'utente, poi il token). Niente V11 |
| 2 | `MediaUploadIT.policyReusedAfterConfirmCannotReplaceTheVerifiedObject` | Upload su `staging/<key>`; `ObjectStorage.promoteUpload` copia sulla chiave finale e cancella lo staging, il confirm verifica l'oggetto finale; lifecycle di 1 giorno su `staging/` in `minio-init` |
| 3 | `SosPushIT.raiseCancelCyclesPushOperatorsAtMostThreeTimes` | Al massimo 3 push ogni 15 minuti per escursionista; le altre allerte segnate come notificate (ADR-0021) |
| 4 | `RefugeCapacityIT.oneUserCannotHoldMoreThanFiveActiveBookings` | Al massimo 5 prenotazioni attive per utente, 422 (ADR-0021) |
| 5 | `RefugeCapacityIT.checkInTooFarAheadIsRejected` | Check-in al massimo a 365 giorni, 422; `statement_timeout = 30s` nella `connection-init-sql` |
| 6 | `AuthFlowIT.expiredAccessTokenIsRejected` (token scaduto da 2 s) | `JwtTimestampValidator(Duration.ZERO)` nel decoder: emittente e verificatore sono lo stesso processo |
| 7 | `NearbyHazardsIT.trailHazards*` | `LIMIT 50`, `ORDER BY created_at DESC`; `SUBMITTED` solo al segnalatore e al `PARK_MANAGER` (`CurrentUser.isParkManager`) |

Restano aperte le note di hardening minori (sezione 6 del `REPORT.md`).
