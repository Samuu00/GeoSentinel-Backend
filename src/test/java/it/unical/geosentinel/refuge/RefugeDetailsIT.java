// Test di integrazione delle informazioni descrittive dei rifugi (descrizione, quota, stagione di apertura,
// telefono e servizi offerti), con chiamate HTTP vere e PostGIS in Testcontainers. Controlla che i campi
// arrivino sia nel dettaglio del rifugio sia nella lista dei rifugi vicini, e la loro validazione.
package it.unical.geosentinel.refuge;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica creazione, lettura e validazione dei dettagli descrittivi di un rifugio.
 */
class RefugeDetailsIT extends AbstractIntegrationTest {

    /**
     * Corpo di creazione di un rifugio con tutti i dettagli. La descrizione ha spazi ai lati e i servizi
     * contengono un doppione, per verificare la normalizzazione.
     */
    private static final String DESCRIBED = """
            {"name":"Rifugio Descritto","location":{"lat":39.31,"lon":16.47},"capacity":12,
             "description":"  Rifugio in pietra con la stufa.  ","altitudeM":1650,"season":"Da giugno a settembre",
             "phone":"+39 0984 123 456","amenities":["HEATING","DRINKING_WATER","HEATING"]}
            """;

    /**
     * Un gestore crea un rifugio con tutti i dettagli e un escursionista lo legge. La descrizione deve
     * arrivare senza spazi ai lati, quota, stagione e telefono invariati, e i servizi senza doppioni e
     * nell'ordine in cui sono dichiarati nell'enum.
     */
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
        // Nell'enum DRINKING_WATER viene prima di HEATING, quindi l'ordine di invio non conta.
        assertThat(strings(refuge.get("amenities"))).containsExactly("DRINKING_WATER", "HEATING");
    }

    /**
     * Un rifugio creato senza dettagli deve avere descrizione e quota a {@code null} e la lista dei servizi
     * vuota (un array, non {@code null}), così il client non deve gestire casi diversi.
     */
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

    /** Anche la lista dei rifugi vicini a un punto deve riportare quota e servizi di ciascun rifugio. */
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

    /** Una quota fuori dall'intervallo ammesso (qui 9000 metri) deve dare 400 con un corpo ProblemDetail. */
    @Test
    void altitudeOutOfRangeIsAValidationError() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/refuges", obtainToken(Role.PARK_MANAGER), """
                {"name":"Rifugio Alto","location":{"lat":39.31,"lon":16.47},"capacity":4,"altitudeM":9000}
                """);

        assertThat(result.status()).isEqualTo(400);
        assertThat(isProblemJson(result)).isTrue();
    }

    /** Un servizio che non esiste nell'enum (qui "SAUNA") deve dare 400. */
    @Test
    void unknownAmenityIsABadRequest() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/refuges", obtainToken(Role.PARK_MANAGER), """
                {"name":"Rifugio Spa","location":{"lat":39.31,"lon":16.47},"capacity":4,"amenities":["SAUNA"]}
                """);

        assertThat(result.status()).isEqualTo(400);
    }

    /**
     * Converte un array JSON di stringhe in una lista Java.
     *
     * @param array il nodo JSON di tipo array
     * @return i valori nell'ordine dell'array
     */
    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return values;
    }
}
