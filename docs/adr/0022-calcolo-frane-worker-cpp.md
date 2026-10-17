# ADR-0022: Calcolo del rischio frane in un worker C++ (MPI + pthread) con il database come contratto

- **Stato:** Proposto
- **Data:** 2026-10-17

## Contesto

Si vuole stimare dove una frana è probabile e dove arriverebbe, per capire quali sentieri sono a rischio (M8). Il calcolo
(Monte Carlo su milioni di celle, automa cellulare su centinaia di migliaia di celle per migliaia di passi) è CPU-bound e
si parallelizza bene, a differenza del resto del backend, che è I/O-bound. Il backend Java gestisce anche l'SOS: un
crash o un calcolo lento non devono toccarlo.

## Decisione

- Il calcolo è un programma C++20 separato (`simulation/`): `landslide-sim` (MPI tra processi a strisce di righe,
  pthread dentro ogni processo, solo il thread principale chiama MPI) e `landslide-worker` (non MPI, libpqxx) che prende
  i job e lancia `mpirun`. Gira in un container proprio, profilo `sim` del compose.
- **Il contratto tra Java e C++ è il database:** Java inserisce righe in `simulation_jobs`; il worker le prende con
  `FOR UPDATE SKIP LOCKED` e un lease, scrive i poligoni in `landslide_zones` e chiude il job in una transazione.
  Niente JNI, niente HTTP tra i due, niente porta in `application` (Java non chiama il worker).
- Il C++ lavora in EPSG:32633 (metri); la conversione in EPSG:4326 la fa PostGIS (`ST_Transform`), mai GDAL.
- Il worker usa un ruolo PostgreSQL dedicato (`geosentinel_sim`) con i soli permessi sulle due tabelle.
- L'esposizione dei sentieri (`ST_Intersects` zone × `trails.path`) la calcola il backend in SQL dopo la fine del job.

## Conseguenze

- Un crash del calcolo non ferma il backend; un worker morto viene sostituito alla scadenza del lease.
- Il risultato non dipende dal numero di processi e thread (generatore counter-based, somme in ordine fisso): si può
  testare confrontando i file.
- Seconda toolchain (CMake, OpenMPI, GDAL) con un job di CI proprio; su Windows si sviluppa in WSL2 o nel container.
- **Eccezione dichiarata** alla regola del §1 del piano: la query di esposizione di `landslide` legge la tabella
  `trails` (solo lettura, nessun repository di `trail`), perché il join spaziale deve stare nel database.
- Il calcolo è asincrono: le API rispondono 202 e l'app legge il risultato dopo.
