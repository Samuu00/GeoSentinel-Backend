// Test unitario dell'entità di dominio Booking (prenotazione di posti letto in un rifugio).
// Controlla le regole di business che l'entità applica da sola, senza database e senza Spring:
// numero minimo di notti e finestra temporale in cui una prenotazione si può annullare.
package it.unical.geosentinel.refuge;

import it.unical.geosentinel.refuge.domain.Booking;
import it.unical.geosentinel.refuge.domain.BookingStatus;
import it.unical.geosentinel.refuge.domain.Refuge;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica le regole di creazione e di annullamento di {@link Booking}.
 */
class BookingTest {

    /** Data di arrivo usata da tutte le prenotazioni del test. */
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 10, 10);

    /** Rifugio di prova con 10 posti letto, posizionato in coordinate WGS84 (SRID 4326). */
    private final Refuge refuge = new Refuge("Rifugio Test",
            new GeometryFactory(new PrecisionModel(), 4326).createPoint(new Coordinate(11.85, 46.5)),
            10, UUID.randomUUID());

    /**
     * Una prenotazione con arrivo e partenza nello stesso giorno (zero notti) deve essere rifiutata con una
     * {@link BusinessRuleViolationException}, la stessa regola che il vincolo CHECK della tabella applica nel
     * database.
     */
    @Test
    void zeroNightsIsRejectedLikeTheDdlCheck() {
        assertThatThrownBy(() -> Booking.create(refuge, UUID.randomUUID(), UUID.randomUUID(), CHECK_IN, CHECK_IN, 1))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    /**
     * L'annullamento confronta la data di arrivo con l'istante passato come argomento, non con l'orologio di
     * sistema. Un istante successivo all'arrivo deve far fallire l'annullamento; un istante precedente deve
     * portare la prenotazione nello stato {@link BookingStatus#CANCELLED}.
     */
    @Test
    void cancelUsesTheGivenInstantNotTheSystemClock() {
        Booking booking = Booking.create(refuge, UUID.randomUUID(), UUID.randomUUID(), CHECK_IN, CHECK_IN.plusDays(2), 2);

        assertThatThrownBy(() -> booking.cancel(Instant.parse("2026-10-11T08:00:00Z")))
                .isInstanceOf(BusinessRuleViolationException.class);

        booking.cancel(Instant.parse("2026-10-09T08:00:00Z"));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }
}
