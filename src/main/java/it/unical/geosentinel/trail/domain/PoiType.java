// Enum della categoria di un punto di interesse (POI) mostrato sulla mappa: ripari, sorgenti, punti
// panoramici e simili. L'app usa la categoria per scegliere colore e icona del segnaposto.
package it.unical.geosentinel.trail.domain;

/**
 * Categoria di un punto di interesse.
 */
public enum PoiType {
    /** Bivacco o riparo. */
    SHELTER,
    /** Sorgente o fontana. */
    WATER_SOURCE,
    /** Punto panoramico. */
    VIEWPOINT,
    /** Zona a rischio geologico, ad esempio un canalone. */
    DANGER_ZONE,
    /** Postazione con dotazioni di emergenza, ad esempio un defibrillatore. */
    EQUIPMENT_POINT,
    /** Bacheca informativa o inizio di un sentiero. */
    INFORMATION
}
