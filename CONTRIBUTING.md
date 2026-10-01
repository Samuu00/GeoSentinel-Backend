# Contribuire

## Ambiente

1. JDK 21 e Docker.
2. `cp .env.example .env` e valori propri (mai committare il `.env`).
3. `docker compose up -d`, poi `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.
4. `./mvnw verify` deve essere verde prima di ogni push (circa un minuto, serve Docker).

## Struttura e regole di dipendenza

Package per feature (`identity`, `trail`, `refuge`, `maintenance`, `emergency`, `media`, `notification`,
`shared`), ognuno con `domain`, `application`, `infrastructure`, `web`. Motivazioni in
[ADR-0002](docs/adr/0002-esagonale-pragmatico.md).

- `domain` non dipende dagli altri strati; `web` non usa i repository.
- Una feature usa il service di un'altra, mai i suoi repository.
- Controller e DTO in `web/`, service in `application/`, repository in `infrastructure/persistence/`.

## Convenzioni

- I bean sono singleton senza stato, con campi `private final` e iniezione da costruttore. Il tempo si
  prende dal bean `Clock`: niente `Instant.now()` diretto.
- DTO come record Java, mapping con MapStruct (`unmappedTargetPolicy=ERROR`). Lombok non si usa.
- JPA: `open-in-view=false`, `@ManyToOne(fetch = LAZY)`, `@Enumerated(STRING)` con il suo `CHECK` nel DDL.
  `@Transactional` va sui service, mai sui controller. In cache finiscono solo DTO, e ogni cache va
  registrata con `CacheConfig.typed(...)`.
- Geometrie: `x` è la longitudine, `y` la latitudine. Si creano solo con il bean `GeometryFactory` e le
  distanze si calcolano con `::geography`.
- Jackson è il 3: `tools.jackson.databind.json.JsonMapper`, non `com.fasterxml...ObjectMapper`.
- Gli errori partono da eccezioni di dominio (`shared/domain/exception`) che `GlobalExceptionHandler`
  traduce in RFC 7807. Uno slug nuovo va aggiunto in `ProblemTypes`. I messaggi d'errore sono in inglese,
  commenti e documentazione in italiano.

## API

Ogni modifica alle API cambia [`docs/openapi.json`](docs/openapi.json) e fa fallire `OpenApiContractIT`
finché non si rigenera:

```bash
./mvnw verify -Dtest=Nessuno -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=OpenApiContractIT -Dopenapi.update=true
```

- Ogni `@Operation` ha un `operationId` (verbo + risorsa, unico, mai cambiato) e, se la rotta è protetta,
  `security = @SecurityRequirement(name = "bearerAuth")`.
- Nei record `*Response` i campi mai null hanno `@Schema(requiredMode = REQUIRED)`.
- Ogni `Pageable` è `@ParameterObject @PageableDefault(...)`, con whitelist dei campi ordinabili.
- Niente valori nuovi negli enum delle risposte senza un aggiornamento dell'app.

## Database

- Le migrazioni da `V1` a `V9` sono applicate e non si toccano più: ogni modifica allo schema va in una nuova `V10__...`.
- `ddl-auto=validate` non controlla `NOT NULL` né lunghezze: vanno replicati a mano nel DDL.
- Il seed di sviluppo `db/seed/R__local_seed.sql` è repeatable e si può modificare.

## Test

| Tipo | Nome | Come |
|---|---|---|
| Unit | `*Test` | JUnit 5 + Mockito, nessun container (`./mvnw test`) |
| Integrazione | `*IT` | Estendono `AbstractIntegrationTest`: PostGIS e Redis veri con Testcontainers, richieste HTTP vere con `call(...)` |

- Niente `@Transactional` negli IT; la pulizia (`TRUNCATE` + `FLUSHALL`) avviene prima di ogni test.
- Concorrenza con `runConcurrently(n, task)` e `@RepeatedTest(5)`.
- Password e token di prova mai letterali nel JSON (gitleaks li scambia per segreti): passarli con `%s`.
- Una funzione nuova con dati personali aggiunge il suo `...Of(UUID userId)` alla cancellazione dell'account
  (`UserAccountService.deleteAccount`).

## Commit e pull request

- Messaggi in stile [Conventional Commits](https://www.conventionalcommits.org): `feat(media): ...`,
  `fix(identity): ...`, `docs: ...`, `test(it): ...`.
- Una PR deve avere i tre job della CI verdi (`verify`, `docker`, `secrets`).
- Una decisione architetturale nuova si documenta con un ADR ([modello](docs/adr/template.md)).
- Le modifiche visibili vanno nel [CHANGELOG](CHANGELOG.md).
