# ADR-0008: Niente Lombok

- **Stato:** Accettato
- **Data:** 2026-09-21

## Contesto

Lombok e MapStruct sono entrambi annotation processor, e l'ordine tra i due causa errori di compilazione difficili da diagnosticare.

## Decisione

Record Java per i DTO, getter espliciti nelle entità, nessun Lombok.

## Conseguenze

- Un po' più di codice nelle entità, nessun processor da ordinare.
- MapStruct con `unmappedTargetPolicy=ERROR`: un campo dimenticato fa fallire la compilazione.
