package it.unical.geosentinel.refuge;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Rifugi descritti (V11): descrizione, quota, stagione, telefono e servizi, nel dettaglio e nella lista dei vicini
class RefugeDetailsIT extends AbstractIntegrationTest {

    private static final String DESCRIBED = """
            {"name":"Rifugio Descritto","location":{"lat":39.31,"lon":16.47},"capacity":12,
             "description":"  Rifugio in pietra con la stufa.  ","altitudeM":1650,"season":"Da giugno a settembre",
             "phone":"+39 0984 123 456","amenities":["HEATING","DRINKING_WATER","HEATING"]}
            """;

    @Test
    void createdRefugeCarriesItsDescriptionAndAmenitiesInEnumOrder() {
        String manager = obtainToken(Role.PARK_MANAGER);

        HttpResult created = call(HttpMethod.POST, "/api/v1/refuges", manager, DESCRIBED);

        assertThat(created.status()).isEqualTo(201);
        String id = json(created.body()).get("id").asString();
        JsonNode refuge = json(call(HttpMethod.GET, "/api/v1/refuges/" + id, obtainToken(Role.HIKER), null).body());
        assertThat(refuge.get("description").asString()).isEqualTo("Rifugio in pietra con la stufa.");
        assertThat(refuge.get("altitudeM").asInt()).isEqualTo(1650);
        assertThat(refuge.get("season").asString()).isEqualTo("Da giugno a settembre");
        assertThat(refuge.get("phone").asString()).isEqualTo("+39 0984 123 456");
        // senza doppioni, nell'ordine dell'enum (DRINKING_WATER prima di HEATING)
        assertThat(strings(refuge.get("amenities"))).containsExactly("DRINKING_WATER", "HEATING");
    }

    @Test
    void refugeWithoutDetailsHasNullFieldsAndEmptyAmenities() {
        String id = json(call(HttpMethod.POST, "/api/v1/refuges", obtainToken(Role.PARK_MANAGER), """
                {"name":"Rifugio Spoglio","location":{"lat":39.31,"lon":16.47},"capacity":4}
                """).body()).get("id").asString();

        JsonNode refuge = json(call(HttpMethod.GET, "/api/v1/refuges/" + id, obtainToken(Role.HIKER), null).body());

        assertThat(refuge.get("description").isNull()).isTrue();
        assertThat(refuge.get("altitudeM").isNull()).isTrue();
        assertThat(refuge.get("amenities").isArray()).isTrue();
        assertThat(refuge.get("amenities")).isEmpty();
    }

    @Test
    void nearbyListCarriesTheDetailsToo() {
        String manager = obtainToken(Role.PARK_MANAGER);
        String id = json(call(HttpMethod.POST, "/api/v1/refuges", manager, DESCRIBED).body()).get("id").asString();

        JsonNode nearby = json(call(HttpMethod.GET, "/api/v1/refuges/nearby?lat=39.31&lon=16.47&radius=1000",
                obtainToken(Role.HIKER), null).body());

        JsonNode mine = null;
        for (JsonNode r : nearby) if (r.get("id").asString().equals(id)) mine = r;
        assertThat(mine).isNotNull();
        assertThat(mine.get("altitudeM").asInt()).isEqualTo(1650);
        assertThat(strings(mine.get("amenities"))).containsExactly("DRINKING_WATER", "HEATING");
    }

    @Test
    void altitudeOutOfRangeIsAValidationError() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/refuges", obtainToken(Role.PARK_MANAGER), """
                {"name":"Rifugio Alto","location":{"lat":39.31,"lon":16.47},"capacity":4,"altitudeM":9000}
                """);

        assertThat(result.status()).isEqualTo(400);
        assertThat(isProblemJson(result)).isTrue();
    }

    @Test
    void unknownAmenityIsABadRequest() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/refuges", obtainToken(Role.PARK_MANAGER), """
                {"name":"Rifugio Spa","location":{"lat":39.31,"lon":16.47},"capacity":4,"amenities":["SAUNA"]}
                """);

        assertThat(result.status()).isEqualTo(400);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return values;
    }
}
