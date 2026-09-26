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

class MaintenanceTicketTest {

    private MaintenanceTicket newTicket() {
        return MaintenanceTicket.forTrail("Frana", null, TicketPriority.HIGH, mock(Trail.class));
    }

    @Test
    void reassigningInProgressTicketKeepsStatus() {
        MaintenanceTicket ticket = newTicket();
        ticket.assign(UUID.randomUUID());
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ASSIGNED);

        ticket.startWork();
        ticket.assign(UUID.randomUUID());
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    @Test
    void openTicketCannotBeResolvedAndResolvedCannotBeCancelled() {
        MaintenanceTicket ticket = newTicket();
        assertThatThrownBy(() -> ticket.resolve(Instant.EPOCH)).isInstanceOf(BusinessRuleViolationException.class);

        ticket.startWork();
        ticket.resolve(Instant.EPOCH);
        assertThat(ticket.getResolvedAt()).isEqualTo(Instant.EPOCH);
        assertThatThrownBy(ticket::cancel).isInstanceOf(BusinessRuleViolationException.class);
    }

    // Stesso serializer di MaintenanceCacheConfig: la lista generica deve tornare una lista di TicketView
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
