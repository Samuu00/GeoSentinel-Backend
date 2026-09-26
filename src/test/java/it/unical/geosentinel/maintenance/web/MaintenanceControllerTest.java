package it.unical.geosentinel.maintenance.web;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaintenanceControllerTest {

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

    @Test
    void ifMatchAcceptsQuotedWeakAndBareVersions() {
        assertThat(MaintenanceController.parseVersion("\"3\"")).isEqualTo(3);
        assertThat(MaintenanceController.parseVersion("W/\"3\"")).isEqualTo(3);
        assertThat(MaintenanceController.parseVersion(" 3 ")).isEqualTo(3);
        assertThatThrownBy(() -> MaintenanceController.parseVersion("\"abc\"")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> MaintenanceController.parseVersion("*")).isInstanceOf(ResponseStatusException.class);
    }
}
