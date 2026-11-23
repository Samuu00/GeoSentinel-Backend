// Test di integrazione delle migrazioni Flyway su un PostGIS vero avviato con Testcontainers.
// Il profilo di test usa ddl-auto=validate: se il contesto Spring parte, lo schema creato dalle migrazioni
// coincide con le entità JPA. In più il test controlla quali migrazioni sono state applicate e che PostGIS
// calcoli le lunghezze in metri.
package it.unical.geosentinel.shared;

import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica lo schema del database prodotto da Flyway e il funzionamento dell'estensione PostGIS.
 */
class MigrationIT extends AbstractIntegrationTest {

    /**
     * Legge lo storico di Flyway e si aspetta tutte e sole le migrazioni versionate, da 1 a 17, nell'ordine
     * di installazione. I dati di esempio non devono comparire.
     */
    @Test
    void allVersionedMigrationsAreAppliedAndTheSeedIsNot() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        // Le migrazioni ripetibili con i dati di esempio (cartella db/seed) sono attive solo nel profilo
        // "local": nel profilo di test lo storico contiene soltanto quelle versionate.
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17");
    }

    /**
     * Controlla la versione di PostGIS (3.4) e inserisce un sentiero lungo circa un chilometro: la colonna
     * {@code length_m}, calcolata dal database, deve valere circa 1000 metri.
     */
    @Test
    void postgisIsInstalledAndLengthIsComputedInMeters() {
        assertThat(jdbcTemplate.queryForObject("SELECT postgis_lib_version()", String.class)).startsWith("3.4");

        // A 39 gradi di latitudine, 0.0116 gradi di longitudine verso est corrispondono a circa un chilometro.
        insertTrail("Un chilometro", "LINESTRING(16.4800 39.3000, 16.4916 39.3000)");
        Double lengthM = jdbcTemplate.queryForObject("SELECT length_m FROM trails", Double.class);

        assertThat(lengthM).isBetween(950.0, 1050.0);
    }
}
