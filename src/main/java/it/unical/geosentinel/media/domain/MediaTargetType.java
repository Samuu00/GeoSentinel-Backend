// Enum del tipo di entità a cui si può allegare un file. È un enum e non una stringa libera perché il
// service, prima di autorizzare il caricamento, verifica che l'entità indicata esista e che l'utente
// possa allegarle una foto.
package it.unical.geosentinel.media.domain;

/**
 * Tipo dell'entità a cui è allegato un media.
 */
public enum MediaTargetType {
    /** Foto di un sentiero. */
    TRAIL,
    /** Foto allegata a una segnalazione di pericolo. */
    HAZARD_REPORT,
    /** Foto di un rifugio, caricata dal suo gestore. */
    REFUGE,
    /** Foto profilo di un utente. */
    USER
}
