# ADR-0013: Unico client: app Kotlin Multiplatform

- **Stato:** Accettato
- **Data:** 2026-09-28

## Contesto

Non è previsto un frontend web: l'unico client è un'app Android e iOS.

## Decisione

Il contratto è il documento OpenAPI (`docs/openapi.json`), versionato con uno snapshot test, da cui si genera il client Kotlin. Niente CORS né cookie.

## Conseguenze

- Ogni operazione ha un `operationId` stabile; i campi mai null delle risposte sono `required`.
- Aggiungere valori agli enum delle risposte richiede un aggiornamento dell'app.
- Il refresh token sta nel body e l'app lo salva cifrato (Keystore / Keychain).
