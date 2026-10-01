# ADR-0015: Upload delle foto con presigned POST policy

- **Stato:** Accettato
- **Data:** 2026-09-28

## Contesto

Far passare le foto dal backend consuma banda e memoria, e un PUT firmato non permette allo storage di imporre tipo e dimensione.

## Decisione

Il backend firma una POST policy S3 con `content-length-range`, `Content-Type` e `key` vincolati; l'app carica direttamente su MinIO e poi chiama `confirm`. Il backend verifica dimensione e magic bytes e mette in quarantena i file non validi. Chiavi degli oggetti generate dal server, bucket privato, download con URL firmati e `Content-Disposition: attachment`.

## Conseguenze

- MinIO rifiuta da solo i file troppo grandi o di un altro tipo.
- Il POST deve avere `Content-Length` (niente chunked).
- Un job cancella gli upload non confermati.
- MinIO arriva da un fork della community (`pgsty`): le immagini ufficiali non sono più scaricabili.
