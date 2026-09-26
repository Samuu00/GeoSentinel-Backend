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

// Stesso serializer di CacheConfig.typed(...): TrailDetail deve sopravvivere al giro su Redis
class TrailDetailCacheSerializationTest {

    @Test
    void trailDetailRoundTripsThroughTypedCacheSerializer() {
        JsonMapper mapper = JsonMapper.builder().build();
        var serializer = new JacksonJsonRedisSerializer<>(mapper, mapper.constructType(TrailDetail.class));
        TrailDetail detail = new TrailDetail(UUID.randomUUID(), "Sentiero 1", "desc", Difficulty.values()[0],
                TrailStatus.OPEN, GeoJsonLineString.of(List.of(List.of(11.8, 46.5), List.of(11.9, 46.6))), 1234.5, 0L);

        assertThat(serializer.deserialize(serializer.serialize(detail))).isEqualTo(detail);
    }
}
