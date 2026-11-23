// Enum degli stati di un'allerta SOS, salvato come stringa nella colonna status di sos_alerts.
// Gli stati ACTIVE e DISPATCHED sono considerati "aperti"; RESOLVED e CANCELLED chiudono l'allerta.
package it.unical.geosentinel.emergency.domain;

/**
 * Ciclo di vita di un'allerta SOS: ACTIVE, poi DISPATCHED quando parte una squadra, infine RESOLVED o
 * CANCELLED. Le transizioni ammesse sono controllate da {@link SosAlert}.
 */
public enum SosStatus {
    /** Allerta appena lanciata dall'escursionista, ancora senza squadra. */
    ACTIVE,
    /** Un operatore ha assegnato una squadra, che è in movimento. */
    DISPATCHED,
    /** Soccorso concluso: l'allerta è chiusa. */
    RESOLVED,
    /** Allerta annullata (falso allarme o annullamento dell'escursionista o di un operatore). */
    CANCELLED
}
