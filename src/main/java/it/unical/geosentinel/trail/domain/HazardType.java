// Enum del tipo di pericolo che un escursionista può segnalare lungo un sentiero. Il valore è scelto
// dall'utente nell'app al momento della segnalazione.
package it.unical.geosentinel.trail.domain;

/**
 * Categoria di una segnalazione di pericolo.
 */
public enum HazardType {
    /** Frana o smottamento. */
    LANDSLIDE,
    /** Albero caduto sul sentiero. */
    FALLEN_TREE,
    /** Allagamento o tratto sommerso. */
    FLOODING,
    /** Sentiero ceduto o eroso. */
    TRAIL_EROSION,
    /** Neve o ghiaccio non segnalati. */
    SNOW_ICE,
    /** Presenza di fauna pericolosa. */
    WILDLIFE,
    /** Qualsiasi altro ostacolo. */
    OTHER
}
