# ADR-0012: Idempotenza delle POST critiche con `Idempotency-Key`

- **Stato:** Accettato
- **Data:** 2026-09-22

## Contesto

In montagna la rete è instabile e il client ritenta: senza protezione un retry crea un secondo SOS o una seconda prenotazione.

## Decisione

Il client genera un UUID per azione e lo manda nell'header `Idempotency-Key`. La chiave è salvata sulla riga con vincolo `UNIQUE (user_id, idempotency_key)` e un retry restituisce 200 con la risorsa già creata. Obbligatoria sulle prenotazioni (428 se manca), facoltativa sugli SOS.

## Conseguenze

- Nessun Redis: il vincolo del database è atomico e transazionale.
- Stessa chiave con body diverso: 422 `idempotency-key-reused` sulle prenotazioni; sugli SOS si restituisce l'allerta esistente.
- La chiave si cerca dopo il lock, così i retry simultanei si mettono in fila.
