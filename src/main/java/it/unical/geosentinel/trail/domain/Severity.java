// Enum della gravità di una segnalazione di pericolo, scelta da chi segnala. Nell'app il livello
// CRITICAL è l'unico mostrato in rosso, il colore riservato alle emergenze.
package it.unical.geosentinel.trail.domain;

/**
 * Gravità di un pericolo, in ordine crescente.
 */
public enum Severity {
    /** Passaggio rallentato ma ancora percorribile. */
    LOW,
    /** Richiede attenzione e brevi deviazioni. */
    MEDIUM,
    /** Sentiero interrotto o pericolo imminente per l'incolumità. */
    HIGH,
    /** Emergenza ambientale con rischio immediato per la vita. */
    CRITICAL
}
