# ADR-0002: Architettura esagonale pragmatica, package per feature

- **Stato:** Accettato
- **Data:** 2026-09-21

## Contesto

Un'architettura esagonale completa (modello di dominio separato dalle entità, una porta per ogni service) raddoppia classi e mapper senza benefici per un backend con un solo database.

## Decisione

Package per feature (`identity`, `trail`, `refuge`, `maintenance`, `emergency`, `media`, `notification`, più `shared`), ognuno con `domain`, `application`, `infrastructure`, `web`. Le entità JPA sono il modello di dominio. Le porte esistono solo per sistemi esterni sostituibili: `ObjectStorage`, `TokenBlacklist`, `LoginAttemptLimiter`, `PushNotifier`.

## Conseguenze

- Regole: `domain` non dipende dagli altri strati; `web` non usa i repository; una feature usa i service di un'altra, mai i suoi repository.
- Le annotazioni JPA nel dominio sono accettate.
- Le dipendenze tra feature passano da metodi espliciti (`getTrailOrThrow`, `deleteAllOf`, ...).
