package it.unical.geosentinel.shared;

import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Flyway V1-V11 su un vero PostGIS + ddl-auto=validate: se il contesto parte, schema ed entità coincidono.
// Sostituisce GeoSentinelApplicationTests (che cercava il Postgres locale)
class MigrationIT extends AbstractIntegrationTest {

    @Test
    void allVersionedMigrationsAreAppliedAndTheSeedIsNot() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        // il seed (db/seed) vive solo nel profilo local: qui non ci sono migrazioni repeatable
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    }

    @Test
    void postgisIsInstalledAndLengthIsComputedInMeters() {
        assertThat(jdbcTemplate.queryForObject("SELECT postgis_lib_version()", String.class)).startsWith("3.4");

        // ~1 km verso est a 39° di latitudine: 0.0116° di longitudine
        insertTrail("Un chilometro", "LINESTRING(16.4800 39.3000, 16.4916 39.3000)");
        Double lengthM = jdbcTemplate.queryForObject("SELECT length_m FROM trails", Double.class);

        assertThat(lengthM).isBetween(950.0, 1050.0);
    }
}
