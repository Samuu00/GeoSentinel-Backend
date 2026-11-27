-- Prezzo dei rifugi: a persona per notte, in centesimi di euro. NULL = prezzo non impostato (si paga in
-- rifugio). La prenotazione copia il totale al momento della creazione: se il prezzo cambia dopo, chi ha già
-- prenotato paga quello di allora. NULL per le prenotazioni già esistenti e per i rifugi senza prezzo.
ALTER TABLE refuges
    ADD COLUMN price_per_night_cents integer,
    ADD CONSTRAINT chk_refuges_price CHECK (price_per_night_cents IS NULL OR price_per_night_cents BETWEEN 0 AND 100000);

ALTER TABLE bookings
    ADD COLUMN total_price_cents integer,
    ADD CONSTRAINT chk_bookings_total_price CHECK (total_price_cents IS NULL OR total_price_cents >= 0);
