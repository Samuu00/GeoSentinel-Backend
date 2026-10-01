# ADR-0009: Chiavi primarie UUID

- **Stato:** Accettato
- **Data:** 2026-09-21

## Contesto

Gli id sequenziali esposti nell'API sono enumerabili e rivelano i volumi.

## Decisione

Tutte le tabelle hanno chiave `uuid`, generata da Hibernate (`GenerationType.UUID`).

## Conseguenze

- Gli id nelle URL non si indovinano.
- Indici un po' più grandi, irrilevanti per i volumi previsti.
