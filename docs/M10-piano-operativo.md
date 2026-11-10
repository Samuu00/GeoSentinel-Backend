# M10: struttura CMake e fasi operative (rischio frane)

> Stato al 21/10: nessun file di M10 nel repo (`simulation/` e `tools/dem-prep/` non esistono ancora). ADR-0022 e ADR-0023 sono
> ancora **Proposti**. Questo documento è il piano di lavoro: ogni fase è una feature con codice nuovo, scritto da te.
> Riferimenti: [MASTER_PLAN §M10](MASTER_PLAN.md), [ADR-0022](adr/0022-calcolo-frane-worker-cpp.md), [ADR-0023](adr/0023-modello-frane.md).

---

## 1. Struttura del progetto CMake

Il progetto C++ sta in `simulation/`, con un suo `CMakeLists.txt` indipendente da Maven. Build fuori dai sorgenti (`build/`,
da aggiungere a `.gitignore`).

### 1.1 Albero

```
simulation/
├── CMakeLists.txt                 # project(), C++20, dipendenze, target, CTest
├── Dockerfile                     # build: ubuntu:24.04 + cmake, libopenmpi-dev, libgdal-dev, libpqxx-dev
│                                  # runtime: solo le librerie, utente non root
├── config/
│   └── soil-params.json           # intervalli dei parametri (ADR-0023)
├── include/geosentinel/sim/       # interfacce pubbliche
│   ├── Grid.hpp                   # griglia locale: righe proprie + 1 riga di halo sopra e sotto, row-major
│   ├── Decomposition.hpp          # strisce di righe per rank (MPI), blocchi di righe per thread
│   ├── HaloExchange.hpp           # MPI_Sendrecv delle righe di bordo (solo thread principale)
│   ├── ThreadPool.hpp             # T pthread creati una volta, pthread_barrier_t tra le fasi
│   ├── Rng.hpp                    # counter-based: (seed, cella, campione) → numero
│   ├── RasterIo.hpp               # GDAL: finestra per rank, GeoTIFF scritto dal rank 0
│   ├── Polygonize.hpp             # GDALPolygonize + semplificazione → GeoJSON EPSG:32633
│   ├── Terrain.hpp                # pendenza Horn, depressioni, D8, area drenata   (aggiunto)
│   ├── SoilParams.hpp             # lettura di soil-params.json                    (aggiunto)
│   ├── Susceptibility.hpp         # FS + Monte Carlo + classi                      (aggiunto)
│   └── Runout.hpp                 # automa cellulare della colata                  (aggiunto)
├── src/
│   ├── core/                      # libreria geosentinel_sim: l'implementazione dei .hpp sopra (no main)
│   ├── sim/main.cpp               # landslide-sim: sottocomandi prepare / susceptibility / runout
│   └── worker/                    # landslide-worker: main + coda dei job (libpqxx, non MPI)
└── test/
    ├── unit/                      # GoogleTest + CTest, senza MPI
    └── mpi/                       # test MPI: lanciati con mpirun -np 1, 2, 4
```

Le quattro voci marcate "aggiunto" non sono nella struttura di M10.2: servono per M10.3–M10.5. Il piano va aggiornato
quando le fasi le creano.

### 1.2 Target

| Target | Tipo | Link | Note |
|---|---|---|---|
| `geosentinel_sim` | libreria statica | `MPI::MPI_CXX`, `Threads::Threads`, GDAL | tutto il calcolo; nessun `main` |
| `landslide-sim` | eseguibile | `geosentinel_sim` | parla MPI; lo lancia `mpirun` |
| `landslide-worker` | eseguibile | `libpqxx::pqxx` | **non** linka MPI: non deve mai inizializzarlo |
| `geosentinel_sim_unit` | test | `geosentinel_sim`, GoogleTest | `add_test` con CTest |
| `geosentinel_sim_mpi` | test | `geosentinel_sim`, GoogleTest | `add_test` con `${MPIEXEC_EXECUTABLE} -np N` (N = 1, 2, 4) |

### 1.3 Regole di build da fissare nel `CMakeLists.txt`

- `CMAKE_CXX_STANDARD 20`, `CMAKE_CXX_STANDARD_REQUIRED ON`, `CMAKE_CXX_EXTENSIONS OFF`.
- Warning (`-Wall -Wextra -Wpedantic -Werror`) con `target_compile_options(... PRIVATE ...)` solo sui nostri target: un
  `-Werror` globale fa fallire la build per i warning degli header di GDAL o OpenMPI.
- `include(CTest)` e `enable_testing()` solo nel progetto principale (`BUILD_TESTING`), non quando la cartella è
  inclusa da altro.
- `CMAKE_EXPORT_COMPILE_COMMANDS ON`: serve a IntelliJ/CLion per l'analisi del codice.
- Dipendenze con `find_package`: `MPI` (`COMPONENTS CXX`), `Threads`, `GDAL`, `libpqxx`, `GTest`. Il test MPI cerca
  `MPIEXEC_EXECUTABLE` con `find_program`.
- Niente `FetchContent` per il core: su Ubuntu 24.04 le librerie vengono dai pacchetti di sistema (una sola fonte di
  versioni, nessuna rete in fase di build).

### 1.4 Ambiente

- Sviluppo: WSL2 o container. MPI e pthread sono Linux; MS-MPI non si usa.
- Dipendenze di sistema (Ubuntu 24.04): `build-essential cmake libopenmpi-dev libgdal-dev libpqxx-dev libgtest-dev`
  più, se scelta, `nlohmann-json3-dev` (vedi §3, decisioni).
- Trappole: OpenMPI non gira come root; `/dev/shm` di Docker è 64 MB (`shm_size: 1gb` nel compose).

---

## 2. Fasi operative

Ogni fase è una feature sola: codice nuovo, test, checkpoint. Non si passa alla successiva senza il checkpoint verde.

| Fase | Feature | Corrisponde a | Dipende da |
|---|---|---|---|
| 1 | Scheletro CMake e toolchain | M10.2 | — |
| 2 | Griglia e decomposizione per strisce | M10.2 | 1 |
| 3 | Generatore counter-based (`Rng`) | M10.2 | 1 (parallelo a 2) |
| 4 | Scambio di halo (MPI) | M10.2 | 2 |
| 5 | Thread pool (pthread) | M10.2 | 2 |
| 6 | I/O raster e poligonizzazione (GDAL) | M10.2 | 2 |
| 7 | Pendenza Horn parallela | M10.3 | 4, 5, 6 |
| 8 | Depressioni, D8, area drenata | M10.3 | 6 |
| 9 | Suscettibilità: FS e Monte Carlo | M10.4 | 3, 7, 8 |
| 10 | Propagazione: automa cellulare | M10.5 | 4, 5, 6 |
| 11 | Worker: coda, lease, chiusura | M10.6 | 1 + V18 (fase J1) |
| J1–J4 | Backend Java: feature `landslide` | M10.7 | V18 (parallelo al C++) |
| 12 | Validazione e prestazioni | M10.8 | 9, 10, 11 |

Grafo: `1 → 2 → {4, 5, 6}`; `1 → 3`; `{4,5,6} → 7`; `6 → 8`; `{3,7,8} → 9`; `{4,5,6} → 10`; `1 + V18 → 11`;
`V18 → J1 → J2 → J3 → J4`; `{9,10,11} → 12`. Il tool `dem-prep` (M10.1) può partire in qualsiasi momento: le fasi
1–6 e 9–10 si testano con dati sintetici, i checkpoint su dati veri aspettano il DEM.

### Fase 1 — Scheletro CMake e toolchain
- **File nuovi:** `simulation/CMakeLists.txt`, `src/sim/main.cpp` (solo `MPI_Init_thread` con `MPI_THREAD_FUNNELED`, uscita
  con errore se il livello concesso è più basso), `test/unit/smoke_test.cpp`, `build/` in `.gitignore`.
- **Checkpoint:** `cmake --build` e `ctest` verdi in WSL/container.
- **Trappola:** verificare subito che GDAL e libpqxx si trovino con `find_package`, non dopo.

### Fase 2 — Griglia e decomposizione per strisce
- **File nuovi:** `Grid.hpp`, `Decomposition.hpp` (+ `.cpp`), `test/unit/decomposition_test.cpp`.
- **Contratto:** `Decomposition::forRank(rows, ranks, rank) → RowRange{first, count}`, strisce contigue, differenza massima
  di una riga tra i rank.
- **Checkpoint:** unione dei range = tutte le righe; nessuna sovrapposizione; funziona anche con più rank che righe.

### Fase 3 — Generatore counter-based
- **File nuovi:** `Rng.hpp` (+ `.cpp`), `test/unit/rng_test.cpp`.
- **Contratto:** valore funzione solo di `(seed, indice globale cella, indice campione)`, mai di rank o thread (splitmix64 o
  Philox sulla chiave combinata). È ciò che rende il risultato indipendente da P e T.
- **Checkpoint:** stessa chiave → stesso valore; ordine di chiamata irrilevante; distribuzione grossolanamente uniforme.

### Fase 4 — Scambio di halo (MPI)
- **File nuovi:** `HaloExchange.hpp` (+ `.cpp`), `test/mpi/halo_test.cpp`.
- **Contratto:** riga 0 ↔ rank sopra, ultima riga ↔ rank sotto, `MPI_PROC_NULL` ai bordi. Solo il thread principale chiama MPI.
- **Checkpoint:** griglia con valore = indice globale della riga; dopo `exchange` le righe di halo sono quelle giuste con
  `-np 1, 2, 4`.

### Fase 5 — Thread pool (pthread)
- **File nuovi:** `ThreadPool.hpp` (+ `.cpp`), `test/unit/thread_pool_test.cpp`.
- **Contratto:** T thread creati una volta; `pthread_barrier_t` tra le fasi; accumulatori per thread `alignas(64)` (false
  sharing).
- **Checkpoint:** le barriere separano le fasi; ogni blocco di righe è elaborato una sola volta.

### Fase 6 — I/O raster e poligonizzazione (GDAL)
- **File nuovi:** `RasterIo.hpp` (+ `.cpp`), `Polygonize.hpp` (+ `.cpp`), `test/mpi/raster_roundtrip_test.cpp`.
- **Contratto:** ogni rank legge la sua finestra; il rank 0 raccoglie con `MPI_Gatherv` e scrive. Nessun rank tiene la griglia
  intera, tranne il rank 0 in scrittura.
- **Checkpoint:** lettura e riscrittura con `-np 4` = file identico byte per byte sui dati, stessa georeferenziazione.

### Fase 7 — Pendenza Horn parallela
- **File nuovi:** `Terrain.hpp` (+ `.cpp`, funzione `slope`), `test/mpi/slope_test.cpp`.
- **Contratto:** stencil 3×3, halo di 1 riga (fase 4), blocchi di righe (fase 5). Bordo del dominio e nodata → nodata.
- **Checkpoint:** piano inclinato a 30° → 30° ± 0,01° nelle celle interne, con `-np 1` e `-np 4`; su un DEM vero confronto
  con `gdaldem slope -alg Horn`, differenza massima < 0,01°.

### Fase 8 — Depressioni, D8, area drenata
- **File nuovi:** funzioni in `Terrain.cpp` (priority-flood, D8, accumulo topologico), sottocomando `prepare` in
  `src/sim/main.cpp`, `test/unit/drainage_test.cpp`.
- **Contratto:** sequenziale. L'area drenata specifica `a` = area a monte / larghezza cella (metri).
- **Checkpoint:** cono rovesciato sintetico → accumulo al centro = numero di celle valide; GeoTIFF in `${SIM_DATA_DIR}/derived/`.
- **Trappola:** non parallelizzare l'accumulo in questa fase (è facoltativo in fase 12).

### Fase 9 — Suscettibilità: FS e Monte Carlo
- **File nuovi:** `Susceptibility.hpp` (+ `.cpp`), `SoilParams.hpp` (+ `.cpp`, legge `config/soil-params.json`),
  sottocomando `susceptibility` in `src/sim/main.cpp`, `test/unit/safety_factor_test.cpp`, `test/mpi/repro_test.cpp`.
- **Modello:** `w = min(1, R·a/(T·sinθ))`, `FS = (C + cosθ·(1 − w·r)·tanφ)/sinθ`, `r ≈ 0,5`, `P = campioni con FS<1 / N`.
  Celle con θ < 1° → P = 0. Classi `MEDIUM` (0,1 ≤ P < 0,5) e `HIGH` (P ≥ 0,5); `LOW` non si scrive.
- **Checkpoint:**
  - 3 casi calcolati a mano (secco, saturo, piatto);
  - `probability.tif` identico byte per byte con (P, T) = (1,1), (4,1), (2,6), (12,1);
  - più pioggia → P non diminuisce in nessuna cella;
  - piano sintetico: P cresce con la pendenza.

### Fase 10 — Propagazione: automa cellulare
- **File nuovi:** `Runout.hpp` (+ `.cpp`), sottocomando `runout` in `src/sim/main.cpp`, `test/mpi/runout_test.cpp`.
- **Modello:** von Neumann a 4 vicini; sottostati `z` (quota) e `h` (spessore); minimizzazione delle differenze su `z + h` con
  aderenza `p_adh` e rilassamento `p_r`.
- **Passo in due fasi:** (1) i thread calcolano i deflussi; barriera; (2) MPI scambia l'halo dei deflussi; barriera; (3) i
  thread aggiornano `h`, sommando gli entranti **sempre nello stesso ordine**.
- **Arresto:** deflusso totale < ε (`MPI_Allreduce`) o passi massimi.
- **Checkpoint:** volume costante a ogni passo (errore relativo < 1e-9); risultato identico con (P,T) = (1,1), (4,1), (2,6);
  piano inclinato: la colata scende e non risale; test di regressione D10 sul crinale.
- **Trappola:** il punto d'innesco arriva in EPSG:4326: la conversione a 32633 la fa il worker con PostGIS, non GDAL.

### Fase 11 — Worker: coda, lease, chiusura
- **File nuovi:** `src/worker/` (ciclo di presa, lease, chiusura in transazione, gestione errori), test di integrazione con
  il database del compose.
- **Contratto:** presa atomica con `FOR UPDATE SKIP LOCKED`; lease di 10 minuti allungato ogni minuto da un thread con la sua
  connessione; chiusura in una transazione (`DELETE` zone → `INSERT` zone → `UPDATE DONE ... WHERE worker_id = $me`); se
  l'`UPDATE` tocca 0 righe, rollback. Il worker lancia `mpirun -np ${SIM_PROCESSES} landslide-sim ...` e aspetta.
- **Checkpoint:** due worker, 10 job: ognuno eseguito una sola volta; `kill -9` durante il calcolo: il job riparte e finisce
  `DONE` senza zone duplicate; con `geosentinel_sim` `SELECT * FROM users` → permesso negato.
- **Compose:** servizio `landslide-worker` sotto `profiles: ["sim"]`, `shm_size: 1gb`, `${SIM_DATA_DIR}` in sola lettura,
  `landslide-db-init` che fa `ALTER ROLE geosentinel_sim LOGIN PASSWORD` con `-v pw=...` da stdin (non in chiaro nella riga
  di comando).

### Fasi J1–J4 — Backend Java, feature `landslide` (in parallelo al C++)
- **J1:** `V18__landslide.sql` (tabelle `simulation_jobs`, `landslide_zones`, `trail_landslide_risk`, ruolo
  `geosentinel_sim` `NOLOGIN`), domain, `HazardVerifiedEvent` pubblicato da `HazardReportService` alla transizione
  `SUBMITTED → VERIFIED`, `HazardVerifiedListener` `BEFORE_COMMIT`. Test `LandslideJobIT`.
- **J2:** `LandslideResultIngestJob` (ogni 30 s): una query `INSERT ... SELECT` con `ST_Intersects` e `ST_Length`, soglia
  `min-exposed-length-m`, `processed_at`. Test `LandslideIngestIT` (zona finta che taglia un sentiero del seed).
- **J3:** rotte `/api/v1/landslide-risk` (GET pubbliche con ETag, POST 202 + `Location`, 409 `simulation-queue-full`),
  record `GeoJsonMultiLineString` e `GeoJsonMultiPolygon`. Test `LandslideRiskApiIT`. Aggiornare `HttpCacheConfig` **e**
  `PUBLIC_CATALOG`.
- **J4:** pulizia notturna delle suscettibilità superate, push facoltativa `LANDSLIDE_RISK`, `docs/openapi.json` rigenerato,
  `api-guide.md`, `CHANGELOG.md`.

### Fase 12 — Validazione e prestazioni
- **Validazione:** curva ROC contro l'inventario IFFI, AUC in `docs/landslide-validation.md` (obiettivo: AUC > 0,5 e
  documentato, non "certificato").
- **Prestazioni:** `docs/landslide-performance.md`, strong scaling con (P×T) = 1×1 … 3×4, speedup, Amdahl, MPI puro contro ibrido.
- **Facoltativo:** accumulo D8 parallelo, rettangolo attivo della propagazione; solo se le misure lo chiedono.
- **Chiusura:** flusso dal vivo (segnalazione `LANDSLIDE` → verifica → job `DONE` → sentiero `HIGH`), `ctest` e
  `./mvnw verify` verdi, job `simulation` in CI, nessun dato (DEM, GeoTIFF) tracciato da git.

---

## 3. Decisioni da prendere prima della Fase 1

1. **DEM 30 m:** GLO-30 (DSM, alberi compresi) o FABDEM (senza alberi, CC BY-NC-SA, solo uso non commerciale). M10.1 dice
   "da fissare in ADR-0023", ma ADR-0023 non ne parla ancora.
2. **Parser JSON per `soil-params.json`:** il piano non lo prevede. Opzioni: `nlohmann-json` (pacchetto di sistema) oppure un
   formato più semplice (CSV o `key=value`). Un file così piccolo non giustifica molto, ma sceglierlo prima evita che
   entri a metà fase 9.
3. **Parsing degli argomenti CLI:** `argv` a mano (quattro sottocomandi, poche opzioni) oppure `CLI11`. Consiglio: a mano
   finché le opzioni restano poche.
4. **GoogleTest:** `libgtest-dev` di sistema (consigliato, coerente con §1.3) oppure `FetchContent`.
5. **Formato di trasporto delle zone:** M10.4 produce `zones.geojson`, M10.6 inserisce WKB (`ST_GeomFromWKB`). Il worker
   non ha GDAL, quindi leggere GeoJSON richiede un parser. Proposta: il worker passa il GeoJSON a PostGIS con
   `ST_SetSRID(ST_GeomFromGeoJSON(...), 32633)` e poi `ST_Transform`; niente WKB. Va scelto e poi scritto in ADR-0022.

## 4. Problemi trovati nel piano (da correggere nei documenti, non nel codice)

- **Job bloccati in `RUNNING` (M10.6):** la presa considera un job solo con `attempts < 3`, e `FAILED` si scrive solo su
  errore. Se il terzo tentativo muore (crash, `kill -9`), il job resta `RUNNING` con lease scaduto e nessuno lo riprende.
  Serve una regola: un job con lease scaduto e `attempts >= 3` passa a `FAILED`. Da mettere nel job di pulizia o nella
  stessa query di presa.
- **Struttura M10.2 incompleta:** mancano `Terrain`, `SoilParams`, `Susceptibility`, `Runout` (vedi §1.1). Aggiungerli al
  piano.
- **ADR-0023 senza la scelta del DEM** (vedi §3, punto 1).
- **Formato delle zone non coerente** (GeoJSON in M10.4, WKB in M10.6): vedi §3, punto 5.

## 5. Lavoro già chiaro, nessun codice da scrivere ora

- ADR-0022 e ADR-0023 restano `Proposti`: si accettano quando si chiudono i punti di §3 e §4.
- `.gitignore`: `simulation/build/`, `${SIM_DATA_DIR}`.
- `.dockerignore`: `simulation/build/`, dati.
- `.env.example`: `SIM_DATA_DIR`, `SIM_PROCESSES`, `SIM_THREADS`, `SIM_DB_PASSWORD`.
