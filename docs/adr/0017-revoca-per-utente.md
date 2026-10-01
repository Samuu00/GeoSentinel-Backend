# ADR-0017: Revoca per utente degli access token

- **Stato:** Accettato
- **Data:** 2026-10-01

## Contesto

Revocare solo il `jti` del token corrente lascia validi per 15 minuti gli access token degli altri dispositivi: un account cancellato potrebbe ancora prenotare, lanciare SOS o caricare foto.

## Decisione

Redis conserva `bl:user:<id>` = secondo epoch fino al quale i token dell'utente sono revocati, con TTL pari alla durata dell'access token. Il validatore controlla `jti` e revoca per utente con un solo `MGET`, fail-closed. La cancellazione dell'account revoca fino a `now`, il cambio password fino a `now - 1 s` (la sessione nuova nasce nel secondo `now`).

## Conseguenze

- Precisione al secondo, come `iat`.
- Nessuna lettura in più sul database a ogni richiesta.
- Ogni nuova operazione che chiude le sessioni di un utente usa questo meccanismo.
