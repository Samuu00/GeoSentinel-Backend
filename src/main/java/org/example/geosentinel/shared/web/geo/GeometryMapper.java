package org.example.geosentinel.shared.web.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;

@Mapper(componentModel = "spring")
public abstract class GeometryMapper {

    @Autowired
    protected GeometryFactory geometryFactory;

    public Point toPoint(GeoPointDto dto) {
        if (dto == null || dto.lat() == null || dto.lon() == null) {
            return null;
        }
        // JTS: Coordinate(x, y) -> x = longitude, y = latitude
        return geometryFactory.createPoint(new Coordinate(dto.lon(), dto.lat()));
    }

    public GeoPointDto toDto(Point point) {
        if (point == null) {
            return null;
        }
        // point.getX() = longitude, point.getY() = latitude
        return new GeoPointDto(point.getY(), point.getX());
    }

    public LineString toLineString(GeoJsonLineString dto) {
        if (dto == null) {
            return null;
        }
        // shape and ranges already guaranteed by the GeoJsonLineString constructor
        Coordinate[] coordinates = dto.coordinates().stream()
                // GeoJSON: [0] = longitude, [1] = latitude
                .map(position -> new Coordinate(position.get(0), position.get(1)))
                .toArray(Coordinate[]::new);
        return geometryFactory.createLineString(coordinates);
    }

    public GeoJsonLineString toDto(LineString lineString) {
        if (lineString == null) {
            return null;
        }
        // GeoJSON output: [longitude, latitude]
        List<List<Double>> positions = Arrays.stream(lineString.getCoordinates())
                .map(coord -> List.of(coord.getX(), coord.getY()))
                .toList();
        return GeoJsonLineString.of(positions);
    }
}