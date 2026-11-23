// Comandi e viste dello strato applicativo delle emergenze.
// I comandi portano i dati in ingresso dai controller a SosService (già convertiti in geometrie JTS);
// le viste sono le risposte del servizio, che i controller trasformano nei DTO dell'API.
package it.unical.geosentinel.emergency.application.dto;

import it.unical.geosentinel.emergency.domain.RescueTeam;
import it.unical.geosentinel.emergency.domain.SosAlert;
import it.unical.geosentinel.emergency.domain.SosStatus;
import it.unical.geosentinel.emergency.domain.TeamStatus;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.UUID;

/**
 * Contenitore dei record usati da {@code SosService} per i comandi in ingresso e i risultati in uscita.
 */
public class SosCommands {
    /**
     * Richiesta di aiuto di un escursionista.
     *
     * @param location posizione attuale (SRID 4326)
     * @param medicalNotes note mediche facoltative
     */
    public record RaiseSosCommand(
            Point location,
            String medicalNotes
    ){}

    /**
     * Nuova posizione inviata periodicamente dall'app durante un SOS.
     *
     * @param newLocation posizione aggiornata (SRID 4326)
     */
    public record UpdateLocationCommand(Point newLocation){}
    /**
     * Invio di una squadra su un'allerta da parte di un operatore.
     *
     * @param teamId id della squadra da inviare
     */
    public record DispatchTeamCommand(UUID teamId){}

    /**
     * Creazione di una nuova squadra di soccorso (solo amministratori).
     *
     * @param name nome univoco
     * @param callSign nominativo radio
     * @param baseLocation posizione della base (SRID 4326)
     * @param contactNumber numero di telefono
     */
    public record CreateRescueTeamCommand(
            String name,
            String callSign,
            Point baseLocation,
            String contactNumber
    ){}

    /**
     * Vista di una squadra restituita dal servizio. Come tutte le viste, non contiene geometrie JTS
     * (che Jackson non sa serializzare): la posizione è già un {@link GeoPointDto}.
     */
    public record RescueTeamView(
            UUID id,
            String name,
            String callSign,
            TeamStatus status,
            GeoPointDto baseLocation,
            String contactNumber
    ){
        /**
         * Costruisce la vista a partire dall'entità.
         *
         * @param team squadra da convertire
         * @param geo convertitore da geometria JTS a {@link GeoPointDto}
         * @return la vista della squadra
         */
        public static RescueTeamView from(RescueTeam team, GeometryMapper geo) {
            return new RescueTeamView(
                    team.getId(),
                    team.getName(),
                    team.getCallSign(),
                    team.getStatus(),
                    geo.toDto(team.getBaseLocation()),
                    team.getContactNumber()
            );
        }
    }

    /**
     * Vista di un'allerta SOS restituita dal servizio, con la squadra assegnata se presente.
     * {@code hikerId} serve anche al controllo {@code @PostAuthorize} di {@code SosService.getAlert}.
     */
    public record SosAlertView(
            UUID id,
            UUID hikerId,
            GeoPointDto location,
            Instant locationUpdatedAt,
            SosStatus status,
            String medicalNotes,
            RescueTeamView assignedTeam,
            Instant closedAt,
            Instant createdAt
    ){
        /**
         * Costruisce la vista a partire dall'entità. Se c'è una squadra assegnata ne legge i dati, quindi va
         * chiamato dentro una transazione (la squadra è caricata in modo lazy).
         *
         * @param alert allerta da convertire
         * @param geo convertitore delle geometrie
         * @return la vista dell'allerta
         */
        public static SosAlertView from(SosAlert alert, GeometryMapper geo){
            return new SosAlertView(
                    alert.getId(),
                    alert.getHikerId(),
                    geo.toDto(alert.getLocation()),
                    alert.getLocationUpdatedAt(),
                    alert.getStatus(),
                    alert.getMedicalNotes(),
                    alert.getAssignedTeam() != null ? RescueTeamView.from(alert.getAssignedTeam(), geo) : null,
                    alert.getClosedAt(),
                    alert.getCreatedAt()
            );
        }
    }

    /**
     * Risultato di {@code SosService.raiseSos}.
     *
     * @param alert l'allerta creata o quella già esistente
     * @param created false quando la richiesta era un retry con la stessa Idempotency-Key o l'escursionista
     *        aveva già un'allerta aperta; in quel caso il controller risponde 200 invece di 201
     */
    public record SosRaiseResult(SosAlertView alert, boolean created) {}
}
