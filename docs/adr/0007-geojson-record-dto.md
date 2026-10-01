# ADR-0007: GeoJSON con record DTO e MapStruct, senza librerie Jackson-JTS

- **Stato:** Accettato
- **Data:** 2026-09-21

## Contesto

Le librerie che serializzano JTS con Jackson sono per Jackson 2; Boot 4 usa Jackson 3.

## Decisione

Due record (`GeoPointDto`, `GeoJsonLineString`) e un `GeometryMapper` MapStruct che converte da e verso JTS con il bean `GeometryFactory`.

## Conseguenze

- Le view dei service non contengono mai geometrie JTS, così le cache Redis restano serializzabili.
- `GeoJsonLineString` valida la forma nel costruttore.
