package it.unical.geosentinel.shared.web.geo;

import java.util.List;

// GeoJSON LineString: le coordinate sono coppie [longitudine, latitudine].
// Gli invarianti sono verificati nel costruttore: un body malformato fallisce la deserializzazione (400) e non arriva mai al mapper.
public record GeoJsonLineString(String type, List<List<Double>> coordinates) {
    public static final String TYPE = "LineString";

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
        coordinates = coordinates.stream().map(List::copyOf).toList();
    }

    public static GeoJsonLineString of(List<List<Double>> coordinates) {
        return new GeoJsonLineString(TYPE, coordinates);
    }
}
