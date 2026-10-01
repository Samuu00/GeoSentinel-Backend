# ADR-0010: Ordine delle coordinate: longitudine, latitudine

- **Stato:** Accettato
- **Data:** 2026-09-21

## Contesto

L'inversione di latitudine e longitudine è la causa più comune di bug spaziali.

## Decisione

In JTS `x = longitudine` e `y = latitudine`; in GeoJSON `[lon, lat]`. Le geometrie si creano solo con il bean `GeometryFactory` (SRID 4326). Nei DTO i campi si chiamano `lat` e `lon`.

## Conseguenze

- Un test di regressione (`TrailSpatialQueryIT`) fallisce se l'ordine si inverte.
