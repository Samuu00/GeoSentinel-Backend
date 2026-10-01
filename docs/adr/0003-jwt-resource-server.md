# ADR-0003: JWT HS256 con il resource server di Spring Security

- **Stato:** Accettato
- **Data:** 2026-09-21

## Contesto

Un filtro JWT scritto a mano è codice di sicurezza in più da mantenere e testare.

## Decisione

Si usa `spring-boot-starter-oauth2-resource-server` (Nimbus): il framework valida firma, scadenza e issuer. L'applicazione emette i token con `JwtEncoder` (HS256, segreto di almeno 32 byte) e aggiunge un validatore per la blacklist. Claim minimi: `sub` (UUID dell'utente), `roles`, `jti`, `iat`, `exp`, nessun dato personale. Access token di 15 minuti.

## Conseguenze

- HS256 va bene finché chi firma e chi verifica è lo stesso servizio; con un secondo servizio che verifica i token si passa a RS256/EdDSA con JWKS.
- La revoca richiede uno stato esterno: blacklist dei `jti` e revoca per utente su Redis ([ADR-0017](0017-revoca-per-utente.md)).
