// Enum dello stato di percorribilità di un sentiero, impostato dal gestore del parco. Un sentiero CLOSED
// viene escluso dal calcolo dei percorsi e l'app lo disegna tratteggiato.
package it.unical.geosentinel.trail.domain;

/**
 * Stato di un sentiero.
 */
public enum TrailStatus {
    /** Percorribile normalmente. */
    OPEN,
    /** Percorribile con cautela, per la presenza di un pericolo noto. */
    WARNING,
    /** Chiuso: non va percorso. */
    CLOSED
}
