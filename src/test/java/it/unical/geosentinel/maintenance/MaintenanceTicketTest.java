// Test unitario dell'entità MaintenanceTicket (ticket di manutenzione di un sentiero) e della sua
// rappresentazione in cache. Controlla le transizioni di stato ammesse e che la lista dei ticket sopravviva
// alla serializzazione JSON usata per Redis. Non usa Spring né database.
package it.unical.geosentinel.maintenance;

import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.TicketView;
import it.unical.geosentinel.maintenance.domain.MaintenanceTicket;
import it.unical.geosentinel.maintenance.domain.TicketPriority;
import it.unical.geosentinel.maintenance.domain.TicketStatus;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.trail.domain.Trail;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Verifica il ciclo di vita di {@link MaintenanceTicket} e la serializzazione in cache di {@link TicketView}.
 */
class MaintenanceTicketTest {

    /**
     * Crea un ticket aperto ad alta priorità su un sentiero finto.
     *
     * @return un ticket nello stato iniziale
     */
    private MaintenanceTicket newTicket() {
        return MaintenanceTicket.forTrail("Frana", null, TicketPriority.HIGH, mock(Trail.class));
    }

    /**
     * Assegnare un ticket aperto lo porta in {@code ASSIGNED}; riassegnare un ticket già in lavorazione deve
     * cambiare solo l'assegnatario, lasciando lo stato {@code IN_PROGRESS}.
     */
    @Test
    void reassigningInProgressTicketKeepsStatus() {
        MaintenanceTicket ticket = newTicket();
        ticket.assign(UUID.randomUUID());
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ASSIGNED);

        ticket.startWork();
        ticket.assign(UUID.randomUUID());
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    /**
     * Un ticket appena aperto non si può risolvere senza essere passato dalla lavorazione; una volta risolto
     * registra l'istante di risoluzione e non si può più annullare. Le transizioni vietate lanciano una
     * {@link BusinessRuleViolationException}.
     */
    @Test
    void openTicketCannotBeResolvedAndResolvedCannotBeCancelled() {
        MaintenanceTicket ticket = newTicket();
        assertThatThrownBy(() -> ticket.resolve(Instant.EPOCH)).isInstanceOf(BusinessRuleViolationException.class);

        ticket.startWork();
        ticket.resolve(Instant.EPOCH);
        assertThat(ticket.getResolvedAt()).isEqualTo(Instant.EPOCH);
        assertThatThrownBy(ticket::cancel).isInstanceOf(BusinessRuleViolationException.class);
    }

    /**
     * Usa lo stesso tipo di serializer configurato in {@code MaintenanceCacheConfig} per una lista generica.
     * Il risultato atteso è che la lista riletta contenga di nuovo oggetti {@link TicketView} uguali agli
     * originali, e non mappe generiche.
     */
    @Test
    void ticketListRoundTripsThroughTypedCacheSerializer() {
        JsonMapper mapper = JsonMapper.builder().build();
        var serializer = new JacksonJsonRedisSerializer<List<TicketView>>(mapper,
                mapper.getTypeFactory().constructCollectionType(List.class, TicketView.class));
        List<TicketView> views = List.of(new TicketView(UUID.randomUUID(), "Frana", null, TicketPriority.HIGH,
                TicketStatus.OPEN, UUID.randomUUID(), "Sentiero 1", null, null, null, null, null,
                Instant.parse("2026-09-26T10:00:00Z"), 0L));

        assertThat(serializer.deserialize(serializer.serialize(views))).isEqualTo(views);
    }
}
