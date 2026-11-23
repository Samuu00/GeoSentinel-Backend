// Test unitario del DTO GeoJsonLineString, la rappresentazione GeoJSON delle linee (ad esempio il tracciato
// di un sentiero) scambiata con i client, e della sua conversione verso la geometria JTS usata da PostGIS.
// Controlla l'ordine delle coordinate e che i corpi malformati vengano scartati già da Jackson.
package it.unical.geosentinel.shared.web.geo;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifica la deserializzazione di {@link GeoJsonLineString} e la conversione da e verso {@link LineString}
 * con il mapper generato {@code GeometryMapperImpl}.
 */
class GeoJsonLineStringTest {

    /** Mapper JSON con la configurazione di default, come nel resto dei test unitari. */
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Un corpo GeoJSON valido viene convertito in una {@link LineString} con longitudine sull'asse x, latitudine
     * sull'asse y e SRID 4326. Riconvertendo la linea in DTO si deve ottenere lo stesso oggetto di partenza.
     */
    @Test
    void validBodyRoundTripsKeepingLonLatOrder() {
        GeoJsonLineString dto = json.readValue(
                "{\"type\":\"LineString\",\"coordinates\":[[11.85,46.55],[11.86,46.56]]}", GeoJsonLineString.class);

        GeometryMapperImpl mapper = new GeometryMapperImpl();
        mapper.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        LineString line = mapper.toLineString(dto);

        // In GeoJSON e in JTS l'ordine è [longitudine, latitudine]: x è la longitudine, y la latitudine.
        assertEquals(11.85, line.getCoordinateN(0).getX());
        assertEquals(46.55, line.getCoordinateN(0).getY());
        assertEquals(4326, line.getSRID());
        assertEquals(dto, mapper.toDto(line));
    }

    /**
     * Ogni corpo dell'elenco viola una regola diversa: posizione con una sola coordinata, linea con un solo
     * punto, latitudine fuori dall'intervallo ammesso, tipo diverso da {@code LineString}, coordinate assenti.
     * Per tutti ci si aspetta una {@link JacksonException} in fase di lettura, così i dati non validi non
     * arrivano mai al mapper né al database.
     */
    @Test
    void malformedBodiesFailDeserializationInsteadOfReachingTheMapper() {
        String[] bad = {
                "{\"type\":\"LineString\",\"coordinates\":[[11.5],[11.6,46.5]]}",
                "{\"type\":\"LineString\",\"coordinates\":[[11.5,46.5]]}",
                "{\"type\":\"LineString\",\"coordinates\":[[46.5,191.0],[11.6,46.5]]}",
                "{\"type\":\"Point\",\"coordinates\":[[11.5,46.5],[11.6,46.5]]}",
                "{\"type\":\"LineString\"}"
        };
        for (String body : bad) {
            assertThrows(JacksonException.class, () -> json.readValue(body, GeoJsonLineString.class), body);
        }
    }
}
