// Enum dello stato di una prenotazione in un rifugio. Una prenotazione nasce confermata; l'annullamento
// la porta in CANCELLED e libera i posti, perché il calcolo dell'occupazione conta solo quelle confermate.
package it.unical.geosentinel.refuge.domain;

/**
 * Stato di una prenotazione.
 */
public enum BookingStatus {
    /** Prenotazione valida: i suoi ospiti occupano posti letto. */
    CONFIRMED,
    /** Prenotazione annullata: non occupa più posti. */
    CANCELLED
}
