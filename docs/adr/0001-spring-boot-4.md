# ADR-0001: Spring Boot 4 invece di 3.x

- **Stato:** Accettato
- **Data:** 2026-09-21

## Contesto

Il supporto open source di Spring Boot 3.5 è terminato a metà 2026. Un progetto nuovo su 3.x nascerebbe già senza patch di sicurezza.

## Decisione

Si usa Spring Boot 4.1 con Java 21: Spring Framework 7, Spring Security 7, Hibernate 7, Jackson 3 (`tools.jackson.*`), Testcontainers 2, starter modulari.

## Conseguenze

- Jackson 3: il bean è `tools.jackson.databind.json.JsonMapper`; Jackson 2 arriva solo come dipendenza transitiva di springdoc e non esiste come bean.
- Alcune librerie pensate per Jackson 2 (es. i moduli JTS) non si usano: vedi [ADR-0007](0007-geojson-record-dto.md).
- Package spostati in Boot 4 (es. `AutoConfigureMockMvc`) e `jarmode=tools` al posto di `layertools` nel Dockerfile.
