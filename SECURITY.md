# Sicurezza

## Versioni supportate

Solo il ramo `main`. Il progetto non ha ancora versioni rilasciate.

## Modello di sicurezza

| Superficie | Esposizione |
|---|---|
| API REST (`/api/v1`) | Pubblica tramite il client mobile; catalogo di sentieri e POI leggibile senza login |
| Object storage | Solo URL firmati e a scadenza; bucket privato |
| Database, Redis | Mai esposti: solo il backend (porte pubblicate solo su `127.0.0.1`) |
| FCM | In uscita; il payload contiene solo tipo evento e id |

Attori considerati: utente anonimo, utente autenticato malevolo (accesso ai dati altrui, abuso delle API),
attaccante con un token rubato, client che ritenta su rete instabile.

## Controlli

| Area | Controllo |
|---|---|
| Autenticazione | JWT HS256 di 15 minuti validati da Spring Security; refresh token opaco salvato come hash, ruotato, con revoca della famiglia al riuso ([ADR-0003](docs/adr/0003-jwt-resource-server.md), [ADR-0004](docs/adr/0004-refresh-token-opaco.md)) |
| Revoca | Blacklist del `jti` al logout; revoca di tutti i token di un utente alla cancellazione dell'account e al cambio password ([ADR-0017](docs/adr/0017-revoca-per-utente.md)); se Redis non risponde, il token è rifiutato |
| Password | BCrypt, da 12 caratteri a 72 byte UTF-8; login a tempo costante anche per email inesistenti |
| Abusi | Rate limit per IP (IPv6 per rete /64) e per utente, anche sulle richieste con un token rifiutato; limite su login e password attuale per account e IP più uno largo per account, azzerato dopo un accesso riuscito ([ADR-0005](docs/adr/0005-rate-limit-lua.md)) |
| Autorizzazione | Ruoli con `@PreAuthorize`, controlli di proprietà nei service; 404 invece di 403 sulle risorse altrui |
| Input | Bean Validation su ogni DTO, query parametrizzate, whitelist dei campi ordinabili, chiavi degli oggetti generate dal server |
| Upload | Tipo e dimensione imposti dallo storage, verifica dei magic bytes, quarantena, quota per utente, download come allegato ([ADR-0015](docs/adr/0015-presigned-post-policy.md)) |
| Dati personali | Minimizzazione (data di nascita mai salvata), nessun dato personale in JWT, push e log; cancellazione con anonimizzazione; posizioni degli SOS anonimizzate dopo 90 giorni |
| Trasporto | HTTP in chiaro in locale (vedi i limiti noti); il rate limit usa l'indirizzo della connessione e ignora `X-Forwarded-For` |
| Errori | RFC 7807 senza stack trace né dettagli interni |
| Segreti | Solo da variabili d'ambiente; `.env*` ignorati da git; gitleaks su tutta la storia in CI |
| Dipendenze | Dependabot (Maven, Docker, Compose, Actions); versioni delle immagini bloccate |

## Verifiche fatte

- Review di sicurezza manuali a ogni milestone, l'ultima sulle modifiche di M7.6 e M7.7 (01/10): nessuna
  vulnerabilità aperta.
- Test di integrazione su accessi incrociati, revoca, rate limit, contratto degli errori e upload.
- AgentShield sulla configurazione dell'assistente: nessun segreto, permesso o hook a rischio.

Non sono stati fatti penetration test né analisi statica automatica (es. CodeQL, OWASP ZAP).

## Limiti noti

- In sviluppo il traffico è HTTP in chiaro: usare solo dati di prova.
- La registrazione rivela se un'email è già usata (422).
- Sbagliando la password di una vittima da un solo IP si blocca solo quell'IP; per bloccare l'account da ogni
  IP servono più di 100 tentativi all'ora da indirizzi diversi. Con Redis spento il login risponde 429
  (fail-closed, scelta voluta).
- Nessun tetto al numero di token push per utente.
- Le notifiche SOS possono arrivare due volte.
