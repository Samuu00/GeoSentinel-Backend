// Enum dello stato di una segnalazione di pericolo lungo il ciclo di vita gestito da HazardReport:
// SUBMITTED, poi VERIFIED o REJECTED dal personale del parco, e infine RESOLVED quando il pericolo è
// stato rimosso. Solo le segnalazioni VERIFIED sono visibili a tutti sulla mappa.
package it.unical.geosentinel.trail.domain;

/**
 * Stato di una segnalazione di pericolo.
 */
public enum HazardStatus {
    /** Inviata dall'escursionista, in attesa di verifica. */
    SUBMITTED,
    /** Controllata e confermata dal personale del parco. */
    VERIFIED,
    /** Respinta, ad esempio perché è un falso allarme o un duplicato. */
    REJECTED,
    /** Pericolo rimosso o sentiero ripristinato. */
    RESOLVED
}
