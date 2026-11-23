// DTO di un punto geografico usato nelle richieste e nelle risposte dell'API (posizione di un pericolo,
// di un POI, di un rifugio). A differenza di GeoJSON qui i campi hanno un nome, lat e lon, e i vincoli di
// Bean Validation controllano che siano presenti e nel range corretto.
package it.unical.geosentinel.shared.web.geo;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Punto geografico in gradi decimali (WGS 84).
 *
 * @param lat latitudine, tra -90 e 90
 * @param lon longitudine, tra -180 e 180
 */
public record GeoPointDto(
        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
        @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
        Double lat,

        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
        @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
        Double lon
) {
}
