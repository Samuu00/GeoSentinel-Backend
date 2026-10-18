# ADR-0025: Verifica in due passaggi con TOTP

- **Stato:** Accettato
- **Data:** 2026-10-19

## Contesto

Chi vuole proteggere l'account oltre la password deve poterlo fare dall'app. Oggi una password indovinata, riusata da un
altro sito o rubata apre l'account. Lo stesso vale per un account Google compromesso, che dopo ADR-0024 apre anche
l'account collegato.

Vincoli:

- **Nessun costo e nessun provider in più.** Il progetto gira in locale e non ha un contratto SMS.
- **L'email è già il canale del reset della password** (M7.2.2). Come secondo fattore non aggiungerebbe nulla: chi
  controlla la casella può già cambiare la password.
- **Il login è senza stato.** Non esiste una sessione di login in corso, e il backend emette i token solo a fine login.
- **Unico client, l'app KMP** (ADR-0013): il QR lo disegna l'app, che può anche aprire l'app di autenticazione con
  l'URI `otpauth://`.

## Decisione

1. **TOTP (RFC 6238), non SMS né email.** Si usano i parametri che ogni app di autenticazione legge (Google
   Authenticator, Aegis, Password di iOS): HMAC-SHA1, 6 cifre, passo di 30 s, tolleranza di ±1 passo. Il codice è
   scritto con `javax.crypto.Mac`, senza librerie: sono poche righe, verificate dai vettori dell'appendice B
   dell'RFC. Il Base32 (RFC 4648, solo codifica, senza padding) è una classe di 20 righe.

2. **Segreto cifrato, non hashato.** Il segreto (160 bit da `SecureRandom`) va riletto per calcolare il codice. Si
   salva quindi in `user_mfa.secret_enc` cifrato con AES-256-GCM, con un IV casuale di 12 byte in testa. La chiave
   `MFA_ENCRYPTION_KEY` (32 byte in base64) è diversa da `JWT_SECRET` e viene controllata all'avvio: se manca o non
   sono 32 byte l'app non parte. Un testo cifrato alterato dà un errore, mai un segreto sbagliato (tag GCM).

3. **Login senza stato.** Niente token di sfida né tabella delle sfide. Il codice si chiede solo dopo la password
   giusta: con la 2FA attiva e il codice assente la risposta è 401 `mfa-required`, e l'app **rimanda la stessa
   richiesta** con `mfaCode`. Lo stesso campo accetta il TOTP (6 cifre) e il codice di recupero (tutto il resto).

4. **Vale su ogni accesso all'account**, anche su `POST /auth/google`, sia per un'identità già collegata sia per il
   collegamento di un account esistente. La verifica avviene **prima** di collegare o di aprire la sessione:
   altrimenti chi controlla l'account Google della vittima salterebbe il secondo fattore. Un account creato da Google
   nasce senza 2FA. Il refresh non chiede il codice, perché la sessione è già verificata.

5. **Anti-replay sotto lock.** Ogni verifica prende prima il lock sulla riga dell'utente (`findByIdForUpdate`, ordine
   di ADR-0021). Un TOTP vale solo se il suo passo è maggiore di `last_used_step`, che poi si aggiorna. Lo stesso
   codice non vale due volte: nemmeno in due login paralleli, né al login dopo essere stato usato per attivare la 2FA.
   Il confronto è in tempo costante e scorre sempre tutta la finestra.

6. **10 codici di recupero, monouso.** Si generano con l'alfabeto Crockford di `PasswordResetCode`
   (`XXXXXXXX-XXXXXXXX`, circa 80 bit) e si mostrano una volta sola. Si salva solo l'hash SHA-256: con 80 bit
   casuali BCrypt non aggiunge sicurezza e costerebbe 10 confronti lenti a ogni tentativo. Il consumo è un `UPDATE`
   condizionale (`used_at IS NULL`), quindi due richieste parallele non usano lo stesso codice. Si rigenerano solo con
   un TOTP, e allora i vecchi smettono di valere.

7. **Limite proprio sui codici.** Chi arriva al codice conosce già la password, quindi il limite del login (10 ogni
   15 minuti) non basta: lascerebbe circa 960 tentativi al giorno. Il limite è di **5 codici all'ora per account** su
   login, `disable` e `recovery-codes`: token bucket Redis `rl:mfa:<userId>`, fail-closed, 429 con `Retry-After`. Un
   codice giusto azzera il bucket. Il 401 `mfa-required` (codice assente) non conta come errore.

8. **Avvisi per email**, inviati dopo il commit con il mailer di M7.2.2 e senza alcun dato del tentativo (né IP né
   orario):
   - al **terzo codice sbagliato del login** in un'ora: "qualcuno ha la tua password". Al massimo un'email per
     finestra; il contatore `mfa:fails:<userId>` si incrementa con uno script Lua atomico, che fa `INCR` ed `EXPIRE`
     insieme. Perché l'evento sopravviva al 401, `login` e `loginWithGoogle` hanno
     `noRollbackFor = InvalidMfaCodeException.class`;
   - all'**attivazione e alla disattivazione**: chi ruba un access token può attivare la 2FA e chiudere fuori il
     titolare, e l'avviso glielo dice subito;
   - al **reset da ADMIN**.

9. **Telefono e codici persi insieme: reset da ADMIN.** `POST /admin/mfa-resets` (solo `ADMIN`) cancella la 2FA
   dell'utente e chiude tutte le sue sessioni (refresh token e `revokeAllIssuedUntil`, ADR-0017). L'identità si
   verifica **fuori dall'app**, di persona o per telefono: è una procedura, non codice. Un `ADMIN` non azzera la
   propria 2FA (422 `business-rule`): serve un secondo `ADMIN`. Nel log restano solo gli id dell'ADMIN e
   dell'utente. Si scarta "il reset della password via email azzera anche la 2FA": il secondo fattore varrebbe quanto
   la casella email.

10. **Facoltativa per tutti i ruoli**, personale compreso.

Contratto: `POST /users/me/mfa/setup` (`startMfaSetup`), `/enable` (`enableMfa`), `/disable` (`disableMfa`),
`/recovery-codes` (`regenerateRecoveryCodes`); `POST /admin/mfa-resets` (`resetUserMfa`); `mfaCode` facoltativo in
`LoginRequest` e `GoogleLoginRequest`; `UserProfileResponse.mfaEnabled`. Slug nuovi: `mfa-required` (401),
`invalid-mfa-code` (401 al login, 422 sulle rotte `/mfa`), `mfa-already-enabled` (409). Dati in V15 (`user_mfa`,
`mfa_recovery_codes`).

## Conseguenze

- **Più facile:** nessun costo né provider; ogni app di autenticazione funziona; il login resta senza stato e la
  sessione (ADR-0003, ADR-0004) non cambia.
- **Più difficile:** una chiave in più da custodire. Se si perde `MFA_ENCRYPTION_KEY`, tutte le 2FA attive diventano
  illeggibili e vanno azzerate da un ADMIN.
- **Rischio accettato:** il 401 `mfa-required` conferma la password a chi la indovina. È il comportamento standard ed
  è frenato dal limite del login.
- **Rischio accettato:** attivare la 2FA non chiede la password. Gli account solo Google non ne hanno una; lo
  attenuano l'avviso per email e il reset da ADMIN.
- **Rischio accettato:** l'attivazione non chiude le altre sessioni. Chi teme un furto cambia la password, che le
  revoca tutte (ADR-0017).
- **Rischio accettato:** il segreto cifrato non è legato all'utente (nessun AAD). Chi può scrivere nel database
  potrebbe copiare il proprio segreto sulla riga di un altro, ma con quell'accesso potrebbe già cambiare l'hash della
  password.
- **Rischio accettato:** il reset da ADMIN si fida della verifica d'identità fatta fuori dall'app.
- **Non coperto:** rotazione di `MFA_ENCRYPTION_KEY`, WebAuthn/passkey, 2FA obbligatoria per il personale, "ricorda
  questo dispositivo".
