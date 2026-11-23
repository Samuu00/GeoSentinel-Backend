// Mapper MapStruct che converte le geometrie JTS usate dalle entità (Point, LineString) nei DTO dell'API
// (GeoPointDto, GeoJsonLineString) e viceversa. È l'unico punto in cui si gestisce l'ordine delle
// coordinate: in JTS x è la longitudine e y la latitudine, in GeoJSON la posizione è [lon, lat].
package it.unical.geosentinel.shared.web.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;

/**
 * Conversioni tra geometrie JTS e DTO geografici. È una classe astratta perché MapStruct genera
 * l'implementazione e la registra come bean Spring; gli altri mapper la usano tramite {@code uses}.
 */
@Mapper(componentModel = "spring")
public abstract class GeometryMapper {

    /** Factory con SRID 4326, l'unica da usare per creare geometrie (definita in GeometryConfig). */
    @Autowired
    protected GeometryFactory geometryFactory;

    /**
     * Converte un punto dell'API in un {@link Point} JTS.
     *
     * @param dto punto con latitudine e longitudine
     * @return il punto JTS, oppure {@code null} se il DTO o una delle coordinate mancano
     */
    public Point toPoint(GeoPointDto dto) {
        if (dto == null || dto.lat() == null || dto.lon() == null) {
            return null;
        }
        // In JTS Coordinate(x, y) ha x = longitudine e y = latitudine.
        return geometryFactory.createPoint(new Coordinate(dto.lon(), dto.lat()));
    }

    /**
     * Converte un {@link Point} JTS nel DTO dell'API.
     *
     * @param point punto JTS
     * @return il DTO, oppure {@code null} se il punto è {@code null}
     */
    public GeoPointDto toDto(Point point) {
        if (point == null) {
            return null;
        }
        // getX() è la longitudine, getY() la latitudine.
        return new GeoPointDto(point.getY(), point.getX());
    }

    /**
     * Converte una LineString GeoJSON in una {@link LineString} JTS.
     *
     * @param dto linea GeoJSON già validata
     * @return la linea JTS, oppure {@code null} se il DTO è {@code null}
     */
    public LineString toLineString(GeoJsonLineString dto) {
        if (dto == null) {
            return null;
        }
        // Forma e range delle coordinate sono già stati verificati dal costruttore di GeoJsonLineString.
        Coordinate[] coordinates = dto.coordinates().stream()
                // In GeoJSON l'indice 0 è la longitudine e l'indice 1 la latitudine.
                .map(position -> new Coordinate(position.get(0), position.get(1)))
                .toArray(Coordinate[]::new);
        return geometryFactory.createLineString(coordinates);
    }

    /**
     * Converte una {@link LineString} JTS nel formato GeoJSON dell'API.
     *
     * @param lineString linea JTS
     * @return la linea GeoJSON con posizioni {@code [lon, lat]}, oppure {@code null}
     */
    public GeoJsonLineString toDto(LineString lineString) {
        if (lineString == null) {
            return null;
        }
        List<List<Double>> positions = Arrays.stream(lineString.getCoordinates())
                .map(coord -> List.of(coord.getX(), coord.getY()))
                .toList();
        return GeoJsonLineString.of(positions);
    }
}
