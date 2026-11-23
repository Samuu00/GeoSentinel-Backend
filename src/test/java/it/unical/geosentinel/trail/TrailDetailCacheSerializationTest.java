// Test unitario della serializzazione del dettaglio di un sentiero verso la cache Redis.
// Il dettaglio viene salvato in cache con un serializer JSON tipizzato: il test controlla che l'oggetto
// riletto sia identico a quello scritto, senza bisogno di un Redis vero.
package it.unical.geosentinel.trail;

import it.unical.geosentinel.shared.web.geo.GeoJsonLineString;
import it.unical.geosentinel.trail.application.dto.TrailCommands.TrailDetail;
import it.unical.geosentinel.trail.domain.Difficulty;
import it.unical.geosentinel.trail.domain.TrailStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica che {@link TrailDetail} sopravviva al passaggio serializzazione e deserializzazione con lo stesso
 * tipo di serializer configurato in {@code CacheConfig.typed(...)}.
 */
class TrailDetailCacheSerializationTest {

    /**
     * Serializza un dettaglio completo (con geometria GeoJSON) e lo deserializza: il risultato atteso è un
     * oggetto uguale all'originale, quindi nessun campo si perde nel giro sulla cache.
     */
    @Test
    void trailDetailRoundTripsThroughTypedCacheSerializer() {
        JsonMapper mapper = JsonMapper.builder().build();
        var serializer = new JacksonJsonRedisSerializer<>(mapper, mapper.constructType(TrailDetail.class));
        TrailDetail detail = new TrailDetail(UUID.randomUUID(), "Sentiero 1", "desc", Difficulty.values()[0],
                TrailStatus.OPEN, GeoJsonLineString.of(List.of(List.of(11.8, 46.5), List.of(11.9, 46.6))), 1234.5, 0L);

        assertThat(serializer.deserialize(serializer.serialize(detail))).isEqualTo(detail);
    }
}
