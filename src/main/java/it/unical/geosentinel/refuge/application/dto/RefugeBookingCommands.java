// Oggetti di trasferimento tra controller e service per rifugi e prenotazioni: i comandi di creazione e
// di prenotazione e le viste restituite dal service, che non contengono entità né geometrie JTS.
package it.unical.geosentinel.refuge.application.dto;

import it.unical.geosentinel.refuge.domain.Booking;
import it.unical.geosentinel.refuge.domain.BookingStatus;
import it.unical.geosentinel.refuge.domain.Refuge;
import it.unical.geosentinel.refuge.domain.RefugeAmenity;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import org.locationtech.jts.geom.Point;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contenitore dei record usati dal service dei rifugi e delle prenotazioni.
 */
public class RefugeBookingCommands {
    /**
     * Dati per creare un rifugio.
     *
     * @param name        nome
     * @param location    posizione con SRID 4326
     * @param capacity    posti letto
     * @param managerId   gestore del rifugio
     * @param description descrizione facoltativa
     * @param altitudeM   quota in metri, facoltativa
     * @param season      periodo di apertura, facoltativo
     * @param phone       telefono, facoltativo
     * @param amenities   servizi, facoltativi
     */
    public record CreateRefugeCommand(
            String name,
            Point location,
            Integer capacity,
            UUID managerId,
            String description,
            Integer altitudeM,
            String season,
            String phone,
            List<RefugeAmenity> amenities
    ) {}
    /**
     * Dati di una prenotazione.
     *
     * @param refugeId rifugio
     * @param checkIn  giorno di arrivo
     * @param checkOut giorno di partenza
     * @param guests   numero di ospiti
     */
    public record BookingCommand(
            UUID refugeId,
            LocalDate checkIn,
            LocalDate checkOut,
            Integer guests
    ) {}
    /**
     * Vista di un rifugio. La posizione è un GeoPointDto e non un Point JTS, che non si serializza in JSON.
     *
     * @param id          identificativo
     * @param name        nome
     * @param location    posizione
     * @param capacity    posti letto
     * @param managerId   gestore
     * @param description descrizione
     * @param altitudeM   quota in metri
     * @param season      periodo di apertura
     * @param phone       telefono
     * @param amenities   servizi, nell'ordine dell'enum
     */
    public record RefugeView(
            UUID id,
            String name,
            GeoPointDto location,
            Integer capacity,
            UUID managerId,
            String description,
            Integer altitudeM,
            String season,
            String phone,
            List<RefugeAmenity> amenities
    ) {
        /**
         * Costruisce la vista a partire dall'entità.
         *
         * @param r   rifugio
         * @param geo mapper delle geometrie
         * @return la vista
         */
        public static RefugeView from(Refuge r, GeometryMapper geo) {
            return new RefugeView(r.getId(), r.getName(), geo.toDto(r.getLocation()), r.getCapacity(), r.getManagerId(),
                    r.getDescription(), r.getAltitudeM(), r.getSeason(), r.getPhone(), List.copyOf(r.getAmenities()));
        }
    }
    /**
     * Vista di una prenotazione.
     *
     * @param id       identificativo
     * @param refugeId rifugio
     * @param userId   utente che ha prenotato
     * @param checkIn  giorno di arrivo
     * @param checkOut giorno di partenza
     * @param guests   numero di ospiti
     * @param status   stato
     */
    public record BookingView(
            UUID id,
            UUID refugeId,
            UUID userId,
            LocalDate checkIn,
            LocalDate checkOut,
            int guests,
            BookingStatus status
    ) {
        /**
         * @param b prenotazione
         * @return la vista
         */
        public static BookingView from(Booking b) {
            return new BookingView(
                    b.getId(),
                    b.getRefuge().getId(),
                    b.getUserId(),
                    b.getCheckIn(),
                    b.getCheckOut(),
                    b.getGuests(),
                    b.getStatus()
            );
        }
    }
    /**
     * Esito di una richiesta di prenotazione.
     *
     * @param booking la prenotazione
     * @param created {@code false} se è un ritentativo con la stessa Idempotency-Key: in quel caso il
     *                controller risponde 200 invece di 201
     */
    public record BookingResult(BookingView booking, boolean created) {}
    /**
     * Disponibilità di un rifugio in un intervallo di date.
     *
     * @param refugeId              rifugio
     * @param totalCapacity         posti letto totali
     * @param from                  prima notte
     * @param to                    giorno di fine, escluso
     * @param availableBedsPerNight posti liberi per ogni notte, in ordine di data
     */
    public record AvailabilityView(
            UUID refugeId,
            int totalCapacity,
            LocalDate from,
            LocalDate to,
            Map<LocalDate, Integer> availableBedsPerNight
    ) {}
}
