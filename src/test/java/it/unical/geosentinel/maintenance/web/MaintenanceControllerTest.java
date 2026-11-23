// Test unitario dei due metodi statici di supporto di MaintenanceController: il controllo dei campi di
// ordinamento ammessi nelle liste dei ticket e la lettura della versione dall'header If-Match, usata per
// il controllo di concorrenza ottimistico. Non avvia Spring.
package it.unical.geosentinel.maintenance.web;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica la validazione dei parametri di ordinamento e il parsing dell'header {@code If-Match} del
 * controller dei ticket di manutenzione.
 */
class MaintenanceControllerTest {

    /**
     * L'ordinamento è ammesso solo sui campi della lista bianca (ad esempio {@code createdAt} e {@code title}).
     * Un campo non previsto, un campo inesistente o un percorso verso un'entità collegata ({@code trail.name})
     * devono essere rifiutati con una {@link ResponseStatusException}.
     */
    @Test
    void sortOnlyOnWhitelistedFields() {
        assertThatCode(() -> MaintenanceController.checkSortable(Sort.by("createdAt", "title"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> MaintenanceController.checkSortable(Sort.by("priority")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> MaintenanceController.checkSortable(Sort.by("trail.name")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> MaintenanceController.checkSortable(Sort.by("nonExistent")))
                .isInstanceOf(ResponseStatusException.class);
    }

    /**
     * L'header {@code If-Match} viene accettato tra virgolette, come ETag debole ({@code W/"3"}) o come numero
     * nudo con spazi intorno, e in tutti i casi dà la versione 3. Un valore non numerico o il carattere jolly
     * {@code *} producono invece una {@link ResponseStatusException}.
     */
    @Test
    void ifMatchAcceptsQuotedWeakAndBareVersions() {
        assertThat(MaintenanceController.parseVersion("\"3\"")).isEqualTo(3);
        assertThat(MaintenanceController.parseVersion("W/\"3\"")).isEqualTo(3);
        assertThat(MaintenanceController.parseVersion(" 3 ")).isEqualTo(3);
        assertThatThrownBy(() -> MaintenanceController.parseVersion("\"abc\"")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> MaintenanceController.parseVersion("*")).isInstanceOf(ResponseStatusException.class);
    }
}
