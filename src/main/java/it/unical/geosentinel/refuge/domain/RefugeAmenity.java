// Enum dei servizi offerti da un rifugio. L'elenco è fisso e coincide con il vincolo CHECK della tabella
// refuge_amenities: aggiungere un valore richiede anche un aggiornamento dell'app, perché il client
// generato dall'OpenAPI conosce solo questi.
package it.unical.geosentinel.refuge.domain;

/**
 * Servizio disponibile in un rifugio.
 */
public enum RefugeAmenity {
    /** Acqua potabile. */
    DRINKING_WATER,
    /** Servizio di ristorazione. */
    RESTAURANT,
    /** Cucina a disposizione degli ospiti. */
    KITCHEN,
    /** Riscaldamento. */
    HEATING,
    /** Docce. */
    SHOWER,
    /** Corrente elettrica. */
    ELECTRICITY,
    /** Cani ammessi. */
    DOGS_ALLOWED,
    /** Lenzuola fornite dal rifugio. */
    SHEETS_PROVIDED
}
