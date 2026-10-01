# ADR-0018: Errori RFC 7807 con slug stabili

- **Stato:** Accettato
- **Data:** 2026-09-22

## Contesto

Il client deve distinguere gli errori senza interpretare testi in linguaggio naturale.

## Decisione

Ogni errore è `application/problem+json` con `type = https://geosentinel.dev/problems/<slug>`, `title`, `status`, `detail`, `instance`, `traceId` e, per la validazione, `errors[]`. Gli slug sono stabili e documentati nello schema OpenAPI; i testi restano in inglese.

## Conseguenze

- Il client mappa gli slug su una `sealed class`, mai il `detail`.
- Anche i filtri di sicurezza (401, 403, 429) scrivono `ProblemDetail`.
- Uno slug nuovo richiede un aggiornamento del client.
