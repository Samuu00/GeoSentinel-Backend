// Eccezione della feature refuge: i posti letto di un rifugio non bastano per una prenotazione, oppure
// il gestore prova a ridurre la capacità sotto gli ospiti già confermati. La capacità si controlla notte
// per notte; la risposta è un 409 capacity-exceeded.
package it.unical.geosentinel.shared.domain.exception;

import java.util.UUID;

/**
 * Segnala che un'operazione sui posti letto di un rifugio supererebbe la capacità disponibile.
 * Viene tradotta in 409 {@code capacity-exceeded}.
 */
public class CapacityExceededException extends DomainException {
    /**
     * Costruisce l'errore per una prenotazione che chiede più posti di quelli liberi.
     *
     * @param refugeId  rifugio richiesto
     * @param requested numero di posti richiesti
     * @param available posti liberi nella notte più piena dell'intervallo (un valore negativo viene
     *                  mostrato come zero)
     */
    public CapacityExceededException(UUID refugeId, int requested, int available) {
        super("Cannot book %d beds for refuge '%s'. Only %d beds available in the selected range"
                .formatted(requested, refugeId, Math.max(0, available)));
    }

    /**
     * Costruttore usato dai metodi factory che preparano un messaggio diverso.
     *
     * @param message messaggio già formattato
     */
    private CapacityExceededException(String message) {
        super(message);
    }

    /**
     * Costruisce l'errore per l'aggiornamento della capacità di un rifugio: la nuova capacità non può
     * scendere sotto il numero di ospiti già confermati in una delle notti future.
     *
     * @param refugeId        rifugio da aggiornare
     * @param capacity        capacità richiesta dal gestore
     * @param confirmedGuests ospiti confermati nella notte più piena
     * @return l'eccezione pronta da lanciare
     */
    public static CapacityExceededException belowConfirmedBookings(UUID refugeId, int capacity, int confirmedGuests) {
        return new CapacityExceededException("Cannot set capacity of refuge '%s' to %d: %d guests are already confirmed for one night"
                .formatted(refugeId, capacity, confirmedGuests));
    }
}
