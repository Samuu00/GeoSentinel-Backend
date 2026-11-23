// DTO che rappresenta una linea in formato GeoJSON (tipo LineString), usato dall'API per la traccia dei
// sentieri. Le coordinate seguono la convenzione GeoJSON: ogni posizione è la coppia [longitudine, latitudine].
package it.unical.geosentinel.shared.web.geo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Geometria GeoJSON LineString.
 * Gli invarianti (tipo corretto, almeno due posizioni, coordinate nel range) sono verificati nel
 * costruttore compatto: un body malformato fa fallire la deserializzazione con un 400 e non arriva mai
 * a {@link GeometryMapper}.
 *
 * @param type        sempre {@value #TYPE}
 * @param coordinates lista di posizioni {@code [lon, lat]}
 */
public record GeoJsonLineString(
        @Schema(requiredMode = REQUIRED) String type,
        @Schema(requiredMode = REQUIRED) List<List<Double>> coordinates
) {
    /** Valore obbligatorio del campo {@code type}. */
    public static final String TYPE = "LineString";

    /**
     * Valida tipo e coordinate e rende immutabile la lista.
     *
     * @throws IllegalArgumentException se il tipo non è LineString, se ci sono meno di due posizioni o
     *                                  se una posizione non è una coppia valida di longitudine e latitudine
     */
    public GeoJsonLineString {
        if (!TYPE.equals(type)) {
            throw new IllegalArgumentException("type must be \"" + TYPE + "\"");
        }
        if (coordinates == null || coordinates.size() < 2) {
            throw new IllegalArgumentException("A LineString requires at least two [lon, lat] positions");
        }
        for (List<Double> position : coordinates) {
            if (position == null || position.size() != 2 || position.get(0) == null || position.get(1) == null) {
                throw new IllegalArgumentException("Each position must be exactly [lon, lat]");
            }
            double lon = position.get(0);
            double lat = position.get(1);
            if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
                throw new IllegalArgumentException("Position out of range: [" + lon + ", " + lat + "]");
            }
        }
        // Copia difensiva: il record non deve cambiare se il chiamante modifica le liste originali.
        coordinates = coordinates.stream().map(List::copyOf).toList();
    }

    /**
     * Crea una LineString impostando il tipo in automatico.
     *
     * @param coordinates posizioni {@code [lon, lat]}
     * @return la geometria validata
     */
    public static GeoJsonLineString of(List<List<Double>> coordinates) {
        return new GeoJsonLineString(TYPE, coordinates);
    }
}
