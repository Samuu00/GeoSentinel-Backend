# ADR-0004: Refresh token opaco con rotazione per famiglia

- **Stato:** Accettato
- **Data:** 2026-09-21

## Contesto

Un refresh token a lunga vita, se rubato, permette di restare collegati a tempo indeterminato.

## Decisione

Il refresh token è un valore casuale di 256 bit, salvato solo come hash SHA-256. Ogni uso lo ruota e ne emette uno nuovo della stessa famiglia. Il riuso di un token già ruotato revoca l'intera famiglia (reuse detection). Durata 7 giorni, sessione massima 30 giorni.

## Conseguenze

- Due refresh paralleli con lo stesso token chiudono la sessione: il client deve serializzare i refresh (contratto in `info.description` dell'OpenAPI).
- Un job notturno cancella le famiglie scadute per `family_expires_at`, non per `expires_at`, così la reuse detection resta attiva per tutta la sessione.
- Il token viaggia nel body: adatto a un'app mobile, non a una SPA.
