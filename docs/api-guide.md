# Guida all'API

Per chi sviluppa un client, cioè per ora l'app Kotlin Multiplatform. Schemi, parametri e codici di risposta
stanno nel contratto OpenAPI 3.1, [`openapi.json`](openapi.json), che fa fede. Qui sono spiegati i
comportamenti che dallo schema non si capiscono.

In sviluppo l'API risponde su `http://localhost:8080` (gli indirizzi per emulatore e telefono sono nel
[README](../README.md)), in produzione su `https://<API_DOMAIN>`. Tutte le rotte iniziano con `/api/v1`.
Si parla JSON UTF-8: date `YYYY-MM-DD` (`LocalDate`), istanti ISO-8601 in UTC (`Instant`), coordinate WGS 84
nei campi `lat` e `lon`, percorsi GeoJSON in ordine `[lon, lat]`. Swagger UI è su `/swagger-ui.html`, ma solo
con il profilo `local`.

## Generare il client

Il client si genera dal file committato, non da un server acceso:

```bash
openapi-generator generate -i docs/openapi.json -g kotlin \
  --library multiplatform --additional-properties=dateLibrary=kotlinx-datetime
```

Ogni operazione ha un `operationId` stabile (verbo + risorsa, es. `createBooking`), che diventa il nome del
metodo. I campi mai null delle risposte sono `required`, quindi non nullable in Kotlin. Configurare il
parser JSON con `ignoreUnknownKeys = true`. Gli enum generati sono rigidi: un valore nuovo lato server
richiede un aggiornamento dell'app.

## Autenticazione

| Passo | Operazione | Note |
|---|---|---|
| Registrazione | `POST /auth/register` | `email`, `password` (da 12 caratteri a 72 byte UTF-8), `displayName`, `birthDate`. Crea sempre un `HIKER`. Sotto i 14 anni → 422 `age-requirement-not-met` |
| Login | `POST /auth/login` | Restituisce `accessToken` (JWT, 15 minuti) e `refreshToken` (opaco). Con la verifica in due passaggi attiva serve anche `mfaCode`: vedi [sotto](#verifica-in-due-passaggi) |
| Login con Google | `POST /auth/google` | `idToken` dal SDK Google, `birthDate` solo al primo accesso: senza → 422 `birth-date-required`, si chiede la data e si ripete con lo stesso token. 200 account esistente, 201 nuovo. Email Google non verificata o account già collegato a un altro Google → 422 `business-rule`; personale → 409 `account-managed`; chiavi Google irraggiungibili → 503 `identity-provider-unavailable` ([ADR-0024](adr/0024-login-google.md)) |
| Password dimenticata | `POST /auth/password-reset/request`, poi `/confirm` | La prima risponde sempre 204 (anche per un'email sconosciuta; 3 all'ora per email → 429) e manda un codice di 16 caratteri valido 15 minuti. La seconda (`email`, `code`, `newPassword`) → 204 e tutte le sessioni chiuse, si torna al login; codice sbagliato, scaduto o usato → 422 `invalid-reset-code` (5 errori lo consumano) |
| Richieste | header `Authorization: Bearer <accessToken>` | Mandarlo sempre, anche sulle GET pubbliche: il rate limit conta per utente invece che per IP |
| Rinnovo | `POST /auth/refresh` | Restituisce una coppia nuova; il refresh token vecchio non vale più |
| Logout | `DELETE /devices/{token}` e poi `POST /auth/logout` | Il primo toglie il dispositivo dalle push, il secondo revoca la sessione |

Sul refresh bisogna stare attenti. Ogni refresh token vale una volta sola, e se ne arriva uno già usato il
server lo tratta come un furto: revoca l'intera sessione e serve un nuovo login. Succede anche con due
refresh partiti in parallelo, quindi il client deve farne uno alla volta (con Ktor basta il plugin `Auth`,
`bearer { refreshTokens { } }`). La sessione dura al massimo 30 giorni dal login, poi il refresh risponde 401.

Unica eccezione ([ADR-0019](adr/0019-periodo-di-grazia-refresh.md)): se la connessione cade prima che l'app legga la
risposta del refresh, l'app può riprovare con lo stesso refresh token entro 60 secondi, purché il token nuovo non sia
mai stato usato. Il server emette una coppia nuova e invalida quella persa. In ogni altro caso il refresh non si
ripete da solo.
I token vanno salvati cifrati (Android Keystore, iOS Keychain).

Il cambio password (`POST /users/me/password`, che restituisce token nuovi) e la cancellazione dell'account
(`DELETE /users/me`) invalidano anche gli access token degli altri dispositivi.

### Verifica in due passaggi

Facoltativa per ogni ruolo, con un'app di autenticazione (TOTP: 6 cifre, 30 s). Decisioni in
[ADR-0025](adr/0025-verifica-in-due-passaggi.md).

| Passo | Operazione | Note |
|---|---|---|
| Configurazione | `POST /users/me/mfa/setup` | → `secret` (Base32, per l'inserimento a mano) e `otpauthUri` (da mostrare come QR o da aprire nell'app di autenticazione). Ripeterla prima della conferma cambia il segreto; con la 2FA già attiva → 409 `mfa-already-enabled` |
| Conferma | `POST /users/me/mfa/enable` `{code}` | Un TOTP valido → `codes`: 10 codici di recupero, **mostrati una volta sola** (l'app li fa salvare all'utente). Codice sbagliato → 422 `invalid-mfa-code` |
| Disattivazione | `POST /users/me/mfa/disable` `{code}` | TOTP o codice di recupero → 204 |
| Nuovi codici di recupero | `POST /users/me/mfa/recovery-codes` `{code}` | Solo TOTP → 10 codici nuovi; i vecchi smettono di valere |
| Stato | `GET /users/me` | `mfaEnabled` |

**Login con la 2FA attiva** (vale anche per `POST /auth/google`):
1. l'app manda email e password come sempre;
2. se la password è giusta e la 2FA è attiva, la risposta è 401 `mfa-required`. Arriva solo a chi conosce la
   password: una password sbagliata dà sempre il 401 di credenziali;
3. l'app chiede il codice e **rimanda la stessa richiesta** con `mfaCode`: il TOTP a 6 cifre oppure un codice di
   recupero (`XXXXXXXX-XXXXXXXX`; maiuscole, minuscole e trattino sono indifferenti);
4. codice sbagliato, già usato o scaduto → 401 `invalid-mfa-code`, si chiede di nuovo.

Ogni codice vale una volta sola: se il login va a buon fine ma la risposta si perde, l'app aspetta il codice
successivo, non ripete lo stesso. Il refresh non chiede il codice.

Limiti: 5 codici sbagliati all'ora per account (login, `disable`, `recovery-codes`) → 429 con `Retry-After`, anche con
il codice giusto. Al terzo codice sbagliato del login il titolare riceve un'email; ne riceve una anche quando la 2FA
viene attivata o disattivata.

**Telefono e codici di recupero persi:** l'utente contatta il supporto. Un `ADMIN`, dopo aver verificato l'identità
fuori dall'app, chiama `POST /admin/mfa-resets` `{email}` → 204: la 2FA è cancellata, tutte le sessioni dell'utente
sono chiuse e gli arriva un'email. Email sconosciuta o senza 2FA → 404; un `ADMIN` non può azzerare la propria → 422.

### Ruoli

`HIKER`, `PARK_MANAGER`, `RESCUE_OPERATOR`, `ADMIN`. Gerarchia: `ADMIN` include `PARK_MANAGER` e
`RESCUE_OPERATOR`, che includono `HIKER`. I ruoli stanno nel claim `roles` del JWT.

## Risorse

| Area | Rotte principali | Chi |
|---|---|---|
| Sentieri | `GET /trails?bbox=`, `/trails/nearby`, `/trails/{id}`; `POST`, `PUT /{id}`, `PATCH /{id}/status` | lettura pubblica, scrittura `PARK_MANAGER` |
| POI | `GET /pois/{id}`, `/pois/nearby`, `/pois/trail/{trailId}`; `POST` | lettura pubblica, scrittura `PARK_MANAGER` |
| Segnalazioni | `POST /hazards`; `GET /hazards/{id}`, `/hazards/trail/{trailId}`, `/hazards/mine`, `/hazards/nearby`, `/hazards/pending`; `PATCH /{id}/status` | creazione `HIKER`, verifica e `/pending` `PARK_MANAGER` |
| Rifugi | `GET /refuges/{id}`, `/nearby`, `/{id}/availability`; `POST`; `PATCH /{id}/capacity` | lettura autenticata, scrittura del gestore del rifugio o `ADMIN` |
| Prenotazioni | `POST /bookings`; `GET /bookings/my`, `/{id}`; `DELETE /{id}` | `HIKER`, solo le proprie |
| Ticket | `/maintenance-tickets` (creazione, ricerca, modifica, assegnazione, stati, per sentiero) | `PARK_MANAGER` |
| SOS | `POST /sos`; `PUT /{id}/location`; `DELETE /{id}`; `GET /sos/active` | escursionista, sulla propria allerta |
| SOS (soccorso) | `GET /sos`, `/sos/nearby`, `/sos/{id}`; `PATCH /{id}/dispatch`, `/{id}/resolve` | `RESCUE_OPERATOR` |
| Squadre | `POST /rescue-teams`; `GET /{id}`, `/available-nearby` | creazione `ADMIN`, lettura `RESCUE_OPERATOR` |
| Media | `POST /media/upload-requests`, `/{id}/confirm`; `GET /{id}`, `/{id}/download-url`, `/attached`, `/my`; `DELETE /{id}` | vedi [upload](#upload-delle-foto) |
| Profilo | `GET`, `PATCH`, `DELETE /users/me`; `POST /users/me/password` | autenticato; la cancellazione solo per `HIKER` |
| Verifica in due passaggi | `POST /users/me/mfa/setup`, `/enable`, `/disable`, `/recovery-codes`; `POST /admin/mfa-resets` | autenticato; il reset solo `ADMIN` |
| Dispositivi | `POST /devices`; `DELETE /devices/{token}` | autenticato |

Una risorsa che non si può vedere risponde 404, non 403, così l'API non rivela che esiste. Vale per
prenotazioni, SOS e media di altri utenti e per le segnalazioni rifiutate.

## Errori

Ogni errore è `application/problem+json` ([RFC 7807](https://www.rfc-editor.org/rfc/rfc7807)):

```json
{
  "type": "https://geosentinel.dev/problems/capacity-exceeded",
  "title": "Capacity exceeded",
  "status": 409,
  "detail": "Cannot book 4 beds for refuge '…'. Only 2 beds available in the selected range",
  "instance": "/api/v1/bookings",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

Il client deve decidere in base allo slug, l'ultima parte di `type`. `title` e `detail` sono testo in
inglese e possono cambiare. Gli errori di validazione aggiungono `errors[]`, con `field` e
`message` per ogni campo. Il `traceId` serve a ritrovare la richiesta nei log.

| Slug | Status | Quando |
|---|---|---|
| `validation-error` | 400 | Body o parametri non validi (`errors[]`); anche UUID o numeri malformati negli header |
| `unauthorized` | 401 | Token assente, scaduto o revocato: fare refresh e riprovare una volta |
| `mfa-required` | 401 | Login con password (o Google) giusta e 2FA attiva, senza `mfaCode`: chiedere il codice e ripetere |
| `invalid-mfa-code` | 401 / 422 | Codice della 2FA sbagliato, già usato o fuori tempo: 401 al login, 422 sulle rotte `/users/me/mfa` |
| `forbidden` | 403 | Ruolo insufficiente |
| `not-found` | 404 | Risorsa inesistente o non visibile |
| `conflict` | 409 | Vincolo di unicità violato da richieste concorrenti |
| `capacity-exceeded` | 409 | Rifugio pieno, o capacità ridotta sotto gli ospiti già confermati |
| `alert-already-taken` | 409 | Squadra già inviata su quell'allerta |
| `concurrent-modification` | 409 | `If-Match` con una versione vecchia: rileggere e riprovare |
| `resource-busy` | 409 | Lock non ottenuto entro 3 s: riprovare dopo `Retry-After` |
| `account-managed` | 409 | Cancellazione self-service di un account del personale |
| `mfa-already-enabled` | 409 | Configurazione della 2FA quando è già attiva |
| `business-rule` | 422 | Regola di dominio violata (email già registrata, date non valide, transizione di stato vietata, password attuale errata) |
| `idempotency-key-reused` | 422 | Stessa `Idempotency-Key` con un body diverso |
| `age-requirement-not-met` | 422 | Età sotto la soglia in registrazione |
| `birth-date-required` | 422 | Primo accesso con Google senza `birthDate` |
| `invalid-reset-code` | 422 | Codice di reset sbagliato, scaduto, già usato o con i tentativi finiti |
| `media-quota-exceeded` | 422 | Quota di 1 GB superata |
| `media-unsupported-type` | 415 | Tipo diverso da JPEG, PNG, WebP |
| `media-payload-too-large` | 413 | File oltre 10 MB |
| `precondition-required` | 428 | Manca `Idempotency-Key` o `If-Match` dove è obbligatorio |
| `rate-limit-exceeded` | 429 | Troppe richieste: aspettare `Retry-After` |
| `storage-unavailable` | 503 | Object storage irraggiungibile: riprovare dopo `Retry-After` |
| `identity-provider-unavailable` | 503 | Chiavi pubbliche di Google irraggiungibili: riprovare dopo `Retry-After` |
| `internal-error` | 500 | Errore inatteso (segnalarlo con il `traceId`) |

## Retry e idempotenza

Il client ritenta da solo soltanto `429`, `409 resource-busy` e `503 storage-unavailable`, aspettando almeno
i secondi indicati in `Retry-After`. Gli altri 4xx non ha senso ripeterli identici.

L'header `Idempotency-Key` (un UUID) è obbligatorio su `createBooking` e facoltativo su `raiseSos`. La chiave
si genera una volta per ogni azione dell'utente e si conserva finché l'azione non ha una risposta
definitiva, anche se nel frattempo l'app viene chiusa. La prima richiesta riceve 201, un retry con la stessa
chiave riceve 200 con la risorsa già creata.

Un SOS non viene mai rifiutato. Se l'escursionista ha già un'allerta attiva, `POST /sos` risponde 200 con
quella, e su questa rotta non c'è rate limit.

## Concorrenza ottimistica (ticket)

Le risposte dei ticket hanno un `ETag` (`"<version>"`). Le modifiche (`PUT`, `assign`, `status`) richiedono
`If-Match` con quel valore: senza → 428, malformato → 400, vecchio → 409 `concurrent-modification`.

## Paginazione

Le liste che crescono sono paginate con `page` (da 0), `size` (default 20, massimo 100) e `sort`
(`campo,asc|desc`). Risposta:

```json
{ "content": [ … ], "page": { "size": 20, "number": 0, "totalElements": 42, "totalPages": 3 } }
```

Solo alcuni campi sono ordinabili (es. `checkIn`, `checkOut`, `createdAt` per le prenotazioni); un campo
diverso risponde 400.

## Rete mobile

Le risposte JSON sopra 1 KB arrivano compresse in gzip se la richiesta manda `Accept-Encoding: gzip`.

`GET /trails?bbox=minLon,minLat,maxLon,maxLat` restituisce fino a 200 sentieri che attraversano l'area, ognuno con
un `path` semplificato (circa 5 m) che basta per disegnarlo; il percorso completo è in `GET /trails/{id}` (ADR-0020).

Le GET pubbliche di `/trails` e `/pois` hanno un `ETag` debole e `Cache-Control: no-cache`: rimandando l'ETag
in `If-None-Match` si riceve `304 Not Modified` senza body. Tutte le altre risposte sono `no-store`.

I limiti sono 30 richieste al minuto per IP su login, registrazione e logout, 120 sul refresh e 60 per utente
su tutto il resto. In più ogni account ha 10 tentativi di login (o di password attuale) ogni 15 minuti.

## Upload delle foto

Le foto non passano dall'API: vanno direttamente allo storage. Tipi ammessi `image/jpeg`, `image/png`,
`image/webp` (l'app converte HEIC in JPEG), al massimo 10 MB, quota di 1 GB per utente.

1. `POST /media/upload-requests` con `originalFilename`, `contentType` e `sizeBytes` esatti e, facoltativi
   ma insieme, `entityType` (`TRAIL` solo `PARK_MANAGER`, `HAZARD_REPORT` solo chi ha fatto la segnalazione)
   ed `entityId`. Risposta 201: `mediaId`, `uploadUrl`, `formFields`, `expiresAt`.
2. `POST` `multipart/form-data` verso `uploadUrl`, senza bearer token. Prima tutti i `formFields` così come
   sono (l'ordine non conta), poi la parte `file`, che deve essere l'ultima e avere lo stesso `Content-Type`. La richiesta deve
   avere `Content-Length` (niente chunked). Lo storage risponde 204; un file più grande (`400 EntityTooLarge`)
   o un campo modificato (`403`) li rifiuta lo storage stesso.
3. `POST /media/{mediaId}/confirm`: `READY`, oppure `QUARANTINED` se il contenuto non è l'immagine dichiarata.
   422 se lo storage non ha ancora il file. Si può ripetere: restituisce lo stesso esito.
4. Dopo `expiresAt` (10 minuti) si riparte dal passo 1.

Per scaricare: `GET /media/{id}/download-url` restituisce un URL firmato valido 5 minuti, con
`Content-Disposition: attachment`.

## Notifiche push

L'app registra il token FCM con `POST /devices` (`token`, `platform`: `ANDROID` o `IOS`) a ogni avvio e
quando FCM lo rinnova. Il payload contiene solo `type` e `alertId`; i dettagli si leggono dall'API.

| `type` | A chi | Significato |
|---|---|---|
| `SOS_RAISED` | operatori di soccorso | Nuova allerta: aprire `GET /sos/{alertId}` |
| `SOS_DISPATCHED` | escursionista | Una squadra sta arrivando |
| `SOS_RESOLVED` | escursionista | L'allerta è stata chiusa |

Una notifica può arrivare due volte: l'app deve tollerare i duplicati.

## Versionamento

La versione dell'API è nel path (`/api/v1`). Ogni modifica al contratto aggiorna `openapi.json`, controllato
da un test: un cambiamento incompatibile (campo tolto, tipo cambiato, valore nuovo in un enum di risposta)
richiede un aggiornamento coordinato dell'app.
