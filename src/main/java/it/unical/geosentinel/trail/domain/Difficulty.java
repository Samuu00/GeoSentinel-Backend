// Enum della difficoltà di un sentiero. I quattro valori ricalcano la scala del CAI (Club Alpino
// Italiano), la classificazione usata sui cartelli dei sentieri italiani. Nel database è salvato come
// stringa, con un vincolo CHECK sui valori ammessi.
package it.unical.geosentinel.trail.domain;

/**
 * Difficoltà di un sentiero secondo la scala CAI.
 */
public enum Difficulty {
    /** Turistico (T): percorso facile su strade o mulattiere. */
    EASY,
    /** Escursionistico (E): sentiero che richiede un minimo di allenamento. */
    MODERATE,
    /** Per escursionisti esperti (EE): tratti esposti o su terreno impervio. */
    DIFFICULT,
    /** Per escursionisti esperti con attrezzatura (EEA): vie ferrate e passaggi attrezzati. */
    EXPERT
}
