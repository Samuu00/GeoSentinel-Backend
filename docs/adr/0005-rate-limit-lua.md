# ADR-0005: Rate limit con token bucket su Redis (script Lua)

- **Stato:** Accettato
- **Data:** 2026-09-21

## Contesto

Login e registrazione vanno protetti dagli abusi, e il limite deve restare atomico anche con più istanze.

## Decisione

Token bucket in uno script Lua eseguito su Redis (`scripts/token_bucket.lua`), senza dipendenze aggiuntive. Tre policy configurabili: login e registrazione per IP (30/min, fail-closed), refresh per IP (120/min, fail-closed), il resto per utente o IP (60/min, fail-open). In più 10 tentativi di login ogni 15 minuti per account.

## Conseguenze

- Sotto CGNAT molti telefoni condividono l'IP: il limite per IP è un tetto contro gli abusi, il brute force lo ferma il limite per account.
- Il limite per account vale anche per la password attuale nel cambio password.
- Le risposte 429 portano `Retry-After`.
- `POST /sos` è escluso: un SOS non si rifiuta mai.
