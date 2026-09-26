package it.unical.geosentinel.shared.web.geo;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeoJsonLineStringTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void validBodyRoundTripsKeepingLonLatOrder() {
        GeoJsonLineString dto = json.readValue(
                "{\"type\":\"LineString\",\"coordinates\":[[11.85,46.55],[11.86,46.56]]}", GeoJsonLineString.class);

        GeometryMapperImpl mapper = new GeometryMapperImpl();
        mapper.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        LineString line = mapper.toLineString(dto);

        assertEquals(11.85, line.getCoordinateN(0).getX()); // x = longitudine
        assertEquals(46.55, line.getCoordinateN(0).getY()); // y = latitudine
        assertEquals(4326, line.getSRID());
        assertEquals(dto, mapper.toDto(line));
    }

    @Test
    void malformedBodiesFailDeserializationInsteadOfReachingTheMapper() {
        String[] bad = {
                "{\"type\":\"LineString\",\"coordinates\":[[11.5],[11.6,46.5]]}",       // posizione incompleta
                "{\"type\":\"LineString\",\"coordinates\":[[11.5,46.5]]}",              // una sola posizione
                "{\"type\":\"LineString\",\"coordinates\":[[46.5,191.0],[11.6,46.5]]}", // fuori range
                "{\"type\":\"Point\",\"coordinates\":[[11.5,46.5],[11.6,46.5]]}",       // tipo errato
                "{\"type\":\"LineString\"}"                                              // coordinate mancanti
        };
        for (String body : bad) {
            assertThrows(JacksonException.class, () -> json.readValue(body, GeoJsonLineString.class), body);
        }
    }
}
