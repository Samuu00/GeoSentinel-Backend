# ADR-0006: Geometrie 4326 con query in `geography` e indice GiST sull'espressione

- **Stato:** Accettato
- **Data:** 2026-09-21

## Contesto

`ST_DWithin` su `geometry` con SRID 4326 lavora in gradi, non in metri, e un cast senza indice fa una scansione completa.

## Decisione

Colonne `geometry(..., 4326)`. Le query di distanza usano `col::geography` (metri) e un indice GiST sull'espressione `(col::geography)`.

## Conseguenze

- Raggi in metri, con tetti espliciti (50 km per catalogo e rifugi, 100 km per gli SOS).
- Verificato con `EXPLAIN`: la query usa l'indice sull'espressione.
