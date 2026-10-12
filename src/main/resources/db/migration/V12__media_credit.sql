-- Credito di una foto presa da una fonte esterna (autore, licenza, fonte), da mostrare accanto alla foto: le licenze
-- CC BY e CC BY-SA lo chiedono. NULL per le foto caricate dagli utenti. Non si imposta dall'API: lo scrive chi
-- importa le foto (tools/osm-import/photos.py).
ALTER TABLE media_files ADD COLUMN credit varchar(300);
