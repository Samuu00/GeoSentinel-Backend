# ADR-0024: Login con Google tramite ID token nativo

- **Stato:** Accettato
- **Data:** 2026-10-17

## Contesto

Gli utenti vogliono entrare con il proprio account Google dall'app KMP. Oggi il backend ha solo email e password:
`users.password_hash` è obbligatorio, non esiste una tabella delle identità esterne e il progetto usa solo il resource
server di Spring Security (nessun `oauth2-client`).

Tre vincoli pesano sulla scelta:

- **La registrazione non verifica l'email.** Chiunque può registrare un'email altrui con una password propria. Un
  collegamento automatico per email, senza altre misure, lascerebbe a quell'attaccante una password valida sull'account
  della vittima anche dopo che la vittima è entrata con Google.
- **Il token Google non contiene la data di nascita.** Il controllo dell'età (GDPR art. 8, soglia 14 anni) della
  registrazione non varrebbe per un login Google, che lo aggirerebbe.
- **Il client è pubblico.** Un'app mobile non può custodire un `client_secret`, quindi il flusso con redirect e
  authorization code (`oauth2-client`, pensato per le web app) non fa al caso nostro.

## Decisione

1. **Flusso con ID token nativo.** L'app ottiene l'ID token dal SDK Google (Credential Manager su Android, GoogleSignIn
   su iOS) e lo invia a `POST /api/v1/auth/google`. Il backend verifica la firma con le chiavi pubbliche di Google
   (`https://www.googleapis.com/oauth2/v3/certs`) e i claim `iss` (`https://accounts.google.com` o
   `accounts.google.com`, letto come stringa), `aud`, `exp` (obbligatorio), `iat`. Il backend non ha bisogno di
   nessun segreto Google, solo della lista dei client ID ammessi (`geosentinel.google.client-ids`). Il verificatore è una
   classe di servizio, non un bean `JwtDecoder`, per non creare un secondo decoder accanto a quello del resource server.

2. **Collegamento a un account esistente.** Se l'ID token contiene un'email verificata (`email_verified = true`) che
   corrisponde a un account `HIKER` già esistente, il backend collega l'identità Google e, nella stessa transazione:
   - azzera `password_hash` (l'account si apre solo con Google, o con il reset della password via codice);
   - revoca tutte le sessioni dell'utente (refresh token e access token degli altri dispositivi);
   - apre la sessione della persona che ha appena fatto il login.

   Gli account di personale (`PARK_MANAGER`, `RESCUE_OPERATOR`, `ADMIN`) non si collegano: la risposta è 409
   `account-managed`, come per la cancellazione. Un account già collegato a un altro account Google (stessa email,
   `sub` diverso) non riceve una seconda identità: 422.

   **Un'email non verificata non apre né crea nulla** (422), nemmeno un account nuovo. Altrimenti un account Google
   con l'email di un altro, non verificata, creerebbe l'account con quell'email; quando la vittima entrasse poi con il
   suo Google verificato, il collegamento lascerebbe attaccata anche l'identità dell'attaccante (presa dell'account).

   I primi accessi concorrenti con la stessa email (doppio tap, retry) si mettono in fila su un advisory lock
   transazionale per email (namespace 7002): il secondo trova l'account del primo invece del vincolo `UNIQUE`.

3. **Età nel primo login.** Un login Google che crea un account nuovo richiede `birthDate` nel body, con la stessa regola
   di `register` (sotto la soglia `geosentinel.registration.min-age`: 422 `age-requirement-not-met`, nessun utente
   creato). Il login di un account già esistente non chiede la data. La data è dichiarata dal client, come già accade
   per la registrazione: non è una verifica.

Il contratto dell'endpoint: `POST /api/v1/auth/google`, `operationId = loginWithGoogle`, body `{ idToken, birthDate? }`,
risposta `TokenResponse` (201 per un account nuovo, 200 per uno esistente). Errori: 401 token non valido (firma, `aud`,
`iss`, `exp`), 422 email non verificata, età o account già collegato a un altro Google, 409 `account-managed`, 503 Google non raggiungibile. La rotta è in
`TOKENLESS_AUTH_PATHS` e sotto il rate limit di login, più il limite per account sul `sub`.

## Conseguenze

- **Più facile:** nessun segreto Google nel backend; la sessione (access token, refresh con rotazione, blacklist) si
  riusa com'è; l'app ottiene il login Google senza passare da un browser.
- **Più difficile:** `password_hash` diventa nullable (V14). Il login con password deve trattare l'hash assente senza
  errori né differenze di tempo; `changePassword` per un account senza password va rifiutato e il percorso è il reset
  via codice.
- **Rischio accettato:** l'età è dichiarata dal client. Google non dà la data di nascita nell'ID token.
- **Rischio accettato:** un ID token rubato da un dispositivo compromesso (validità circa un'ora) dà una sessione per
  quell'account, come un access token rubato. Il nonce non si verifica: lo genera e controlla l'app, e il backend non
  ha una sessione di login in corso a cui legarlo.
- **Rischio accettato:** l'account collegato perde la password. Chi la usava dovrà entrare con Google o reimpostarla.
- **Dipendenza esterna:** se Google non risponde, il login Google fallisce con 503; email e password restano disponibili.
- **Da fare dopo questo ADR:** migrazione V14, endpoint e test (fasi 1–5 del piano). Lato utente, nella Google Cloud
  Console: progetto, consent screen in produzione, client OAuth Android (SHA-1), iOS (bundle id) e Web (audience
  dell'ID token su Android). Lato app: il piano va scritto in `../GeoSentinel-Mobile`.
- **Non coperto:** verifica del nonce, login con Apple o altri provider, collegamento volontario da un account già aperto
  (una richiesta futura con una propria decisione).
