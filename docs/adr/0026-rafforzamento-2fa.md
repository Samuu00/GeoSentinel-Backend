# ADR-0026: Rafforzamento della verifica in due passaggi dopo l'audit del 10/11

- **Stato:** Accettato
- **Data:** 2026-11-10
- **Modifica:** ADR-0025 (§7 limite sui codici, rischio accettato sull'attivazione)

## Contesto

Un audit esterno ha proposto cinque piste sulla 2FA. Verificate sul codice:

1. *Il reset della password scavalca la 2FA:* **non è una falla.** `confirmReset` non apre sessioni e la 2FA resta
   al login (ADR-0025 §9).
2. *Blocco della 2FA di una vittima:* **confermata.** Il bucket `rl:mfa:<userId>` era unico per login, `enable`,
   `disable` e `recovery-codes`. Chi conosce la password ne spende uno ogni 12 minuti (la ricarica) e il titolare
   riceve 429 per sempre, anche in sessione. Né il reset della password né quello da ADMIN azzeravano il bucket.
3. *`changePassword` apre una sessione senza 2FA:* **confermata.** Violava la regola "ogni percorso che apre una
   sessione chiede il secondo fattore". Un access token rubato (15 min) più la password diventavano una sessione
   nuova di durata piena, e le sessioni del titolare si chiudevano.
4. *Gestione della 2FA con il solo bearer:* **rischio già accettato** (ADR-0025). Con il solo bearer si cancella
   anche l'account (`DELETE /users/me`), quindi chiedere la password solo qui non alzerebbe la garanzia.
5. *Collegamento Google a un account pre-registrato:* **confermata.** La registrazione non verifica l'email. Chi
   registra `vittima@gmail.com` e attiva la 2FA con il proprio segreto chiude fuori la vittima anche quando questa
   entra con Google: neanche il reset della password basta. Senza 2FA, i token push del telefono dell'attaccante
   restavano sull'account collegato.

## Decisione

1. **Due bucket per account:** `rl:mfa:<userId>` per il login, `rl:mfa-session:<userId>` per le operazioni in
   sessione (`enable`, `disable`, `recovery-codes`, cambio password). Stesso limite (5 all'ora, fail-closed). Un
   codice giusto, un reset della password riuscito e un reset da ADMIN li azzerano entrambi.
2. **Il cambio password chiede il secondo fattore** se la 2FA è attiva: `mfaCode` facoltativo in
   `ChangePasswordRequest` (TOTP o codice di recupero). Se manca o è sbagliato la risposta è 422 `invalid-mfa-code`,
   non 401: un 401 farebbe credere all'app che il token sia scaduto. Metodo `MfaService.verifyStepUp`.
3. **Lo setup prova il possesso dell'email.** `POST /mfa/setup` manda all'email dell'account un codice (alfabeto di
   `PasswordResetCode`, valido 15 minuti, solo l'hash SHA-256 in `user_mfa`, V17). `POST /mfa/enable` chiede
   `emailCode` oltre al TOTP; ogni tentativo passa dal bucket in sessione. Chi ha registrato l'email di un altro non
   riceve il codice, quindi non può attivare la 2FA.
4. **Il collegamento a Google cancella i dispositivi** dell'account (`DeviceService.deleteAllOf`), oltre a password e
   sessioni.

## Conseguenze

- **Contratto cambiato:** `enableMfa` richiede `emailCode` (400 senza); `changePassword` accetta `mfaCode`. L'app
  deve chiedere il codice arrivato per email nello setup, e il codice 2FA nel cambio password quando
  `mfaEnabled = true`. `docs/openapi.json` rigenerato.
- **Più difficile:** senza email configurata (`MAIL_ENABLED=false`) la 2FA non si può attivare, perché il codice non
  parte. Il reset della password ha lo stesso vincolo.
- **Rischio accettato:** un attaccante con la password spende ancora il bucket del login. Il titolare lo sblocca
  cambiando la password (in sessione o via email) e riceve l'avviso al terzo errore.
- **Rischio accettato:** l'account pre-registrato senza 2FA tiene i dati creati dall'attaccante (profilo,
  segnalazioni). Password, sessioni e dispositivi si chiudono al collegamento.
- **Non coperto:** la verifica dell'email alla registrazione, che è la correzione alla radice della pista 5.
