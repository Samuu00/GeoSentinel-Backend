// Servizio applicativo delle emergenze: gestisce il ciclo di vita delle allerte SOS e l'anagrafica delle
// squadre di soccorso. Apre le transazioni, prende i lock nell'ordine corretto, controlla i ruoli con
// Spring Security e pubblica gli eventi da cui partono le notifiche push. Le regole sui cambi di stato
// restano nelle entità SosAlert e RescueTeam.
package it.unical.geosentinel.emergency.application;

import it.unical.geosentinel.emergency.application.dto.SosCommands.CreateRescueTeamCommand;
import it.unical.geosentinel.emergency.application.dto.SosCommands.DispatchTeamCommand;
import it.unical.geosentinel.emergency.application.dto.SosCommands.RaiseSosCommand;
import it.unical.geosentinel.emergency.application.dto.SosCommands.RescueTeamView;
import it.unical.geosentinel.emergency.application.dto.SosCommands.SosAlertView;
import it.unical.geosentinel.emergency.application.dto.SosCommands.SosRaiseResult;
import it.unical.geosentinel.emergency.application.dto.SosCommands.UpdateLocationCommand;
import it.unical.geosentinel.emergency.domain.RescueTeam;
import it.unical.geosentinel.emergency.domain.SosAlert;
import it.unical.geosentinel.emergency.domain.SosAlertRaisedEvent;
import it.unical.geosentinel.emergency.domain.SosAlertStatusChangedEvent;
import it.unical.geosentinel.emergency.domain.SosStatus;
import it.unical.geosentinel.emergency.infrastructure.persistence.RescueTeamRepository;
import it.unical.geosentinel.emergency.infrastructure.persistence.SosAlertRepository;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Casi d'uso delle allerte SOS (lancio, posizione in tempo reale, annullamento, invio della squadra,
 * chiusura) e delle squadre di soccorso (creazione e ricerca). Lo usano {@code SosController},
 * {@code RescueTeamController} e, alla cancellazione di un account, {@code UserAccountService}.
 * <p>
 * Quando servono più lock, l'ordine è sempre lo stesso: prima l'allerta, poi la squadra. Così due
 * operazioni concorrenti (per esempio un invio e un annullamento) non possono bloccarsi a vicenda.
 */
@Service
public class SosService {
    /** Stati in cui un'allerta è considerata aperta. */
    private static final List<SosStatus> ACTIVE_STATUSES = List.of(SosStatus.ACTIVE, SosStatus.DISPATCHED);
    /** Raggio massimo, in metri, della ricerca delle allerte vicine. */
    private static final double MAX_RADIUS_METERS = 100_000;
    /** Numero massimo di push agli operatori per escursionista dentro {@link #PUSH_WINDOW}. */
    static final int MAX_PUSHES_PER_WINDOW = 3;
    /** Finestra di tempo su cui si contano le allerte di uno stesso escursionista. */
    static final Duration PUSH_WINDOW = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(SosService.class);

    private final SosAlertRepository sosAlertRepository;
    private final RescueTeamRepository rescueTeamRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final GeometryMapper geometryMapper;
    private final Clock clock;

    /**
     * @param sosAlertRepository repository delle allerte
     * @param rescueTeamRepository repository delle squadre
     * @param applicationEventPublisher pubblicatore degli eventi di dominio (notifiche push)
     * @param geometryMapper convertitore tra geometrie JTS e DTO dell'API
     * @param clock orologio iniettato, sostituibile nei test
     */
    public SosService(SosAlertRepository sosAlertRepository, RescueTeamRepository rescueTeamRepository,
                      ApplicationEventPublisher applicationEventPublisher, GeometryMapper geometryMapper, Clock clock) {
        this.sosAlertRepository = sosAlertRepository;
        this.rescueTeamRepository = rescueTeamRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.geometryMapper = geometryMapper;
        this.clock = clock;
    }

    /**
     * Lancia un'allerta SOS per l'escursionista.
     * <p>
     * Una richiesta d'aiuto non viene mai rifiutata: un retry con la stessa chiave di idempotenza, oppure un
     * secondo SOS mentre il primo è ancora aperto, restituisce l'allerta esistente con {@code created = false}
     * invece di un errore o di un duplicato. Le richieste dello stesso escursionista vengono messe in fila da un
     * advisory lock di PostgreSQL, così due richieste simultanee (un retry, un doppio tocco) non creano due
     * allerte.
     * <p>
     * Per evitare di inondare gli operatori, se l'escursionista ha già lanciato {@link #MAX_PUSHES_PER_WINDOW}
     * allerte nella {@link #PUSH_WINDOW} la nuova allerta viene salvata ma la push non parte.
     *
     * @param hikerId id dell'escursionista autenticato
     * @param idempotencyKey chiave di idempotenza dell'header, può essere null
     * @param cmd posizione e note mediche
     * @return l'allerta e l'indicazione se è stata appena creata
     */
    @Transactional
    @PreAuthorize("hasRole('HIKER')")
    public SosRaiseResult raiseSos(UUID hikerId, UUID idempotencyKey, RaiseSosCommand cmd) {
        sosAlertRepository.lockHiker(hikerId.getMostSignificantBits() ^ hikerId.getLeastSignificantBits());

        Optional<SosAlert> existing = Optional.ofNullable(idempotencyKey)
                .flatMap(key -> sosAlertRepository.findByHikerIdAndIdempotencyKey(hikerId, key))
                .or(() -> sosAlertRepository.findFirstByHikerIdAndStatusIn(hikerId, ACTIVE_STATUSES));
        if (existing.isPresent()) {
            return new SosRaiseResult(SosAlertView.from(existing.get(), geometryMapper), false);
        }

        // Le allerte recenti si contano prima del salvataggio e sotto il lock dell'escursionista: così un ciclo
        // lancia, annulla, rilancia non può tempestare gli operatori di push. Il rate limit generale dell'API
        // non si applica al POST dell'SOS, quindi questo è l'unico freno.
        Instant now = clock.instant();
        boolean pushAllowed = sosAlertRepository.countByHikerIdAndCreatedAtAfter(hikerId, now.minus(PUSH_WINDOW))
                < MAX_PUSHES_PER_WINDOW;

        SosAlert saved = sosAlertRepository.saveAndFlush(
                SosAlert.raise(hikerId, idempotencyKey, cmd.location(), cmd.medicalNotes(), now));

        if (!pushAllowed) {
            // L'allerta resta comunque visibile nella lista degli operatori. Si imposta notified_at perché
            // altrimenti SosNotificationRecovery la considererebbe non notificata e invierebbe la push.
            sosAlertRepository.markNotified(saved.getId(), now);
            log.warn("SOS push suppressed for alert {}: too many alerts from the same hiker", saved.getId());
            return new SosRaiseResult(SosAlertView.from(saved, geometryMapper), true);
        }

        applicationEventPublisher.publishEvent(new SosAlertRaisedEvent(
                saved.getId(),
                saved.getLocation().getX(),
                saved.getLocation().getY(),
                saved.getCreatedAt()
        ));
        return new SosRaiseResult(SosAlertView.from(saved, geometryMapper), true);
    }

    /**
     * Annulla l'eventuale allerta aperta dell'escursionista. Viene chiamato durante la cancellazione
     * dell'account. Prende lo stesso advisory lock di {@link #raiseSos}, così un SOS lanciato in parallelo non
     * sfugge all'annullamento; poi blocca allerta e squadra nell'ordine consueto, come {@link #cancelSos}.
     *
     * @param hikerId id dell'utente che sta cancellando l'account
     */
    @Transactional
    public void cancelActiveOf(UUID hikerId) {
        sosAlertRepository.lockHiker(hikerId.getMostSignificantBits() ^ hikerId.getLeastSignificantBits());
        sosAlertRepository.findFirstByHikerIdAndStatusIn(hikerId, ACTIVE_STATUSES)
                .flatMap(active -> sosAlertRepository.findByIdForUpdate(active.getId()))
                .ifPresent(alert -> {
                    lockAssignedTeam(alert);
                    alert.cancel(clock.instant());
                });
    }

    /**
     * Aggiorna la posizione di un'allerta aperta. L'app la invia periodicamente mentre l'SOS è in corso.
     *
     * @param alertId id dell'allerta
     * @param hikerId id dell'escursionista autenticato, che deve essere il proprietario dell'allerta
     * @param cmd nuova posizione
     * @return l'allerta aggiornata
     * @throws ResourceNotFoundException se l'allerta non esiste o appartiene a un altro escursionista
     * @throws BusinessRuleViolationException se l'allerta è già chiusa
     */
    @Transactional
    public SosAlertView updateLiveLocation(UUID alertId, UUID hikerId, UpdateLocationCommand cmd) {
        SosAlert alert = sosAlertRepository.findByIdAndHikerIdForUpdate(alertId, hikerId)
                .orElseThrow(() -> new ResourceNotFoundException("SOS alert", alertId));
        alert.updateLocation(cmd.newLocation(), clock.instant());
        return SosAlertView.from(alert, geometryMapper);
    }

    /**
     * Annulla un'allerta su richiesta del suo escursionista. Annullare un'allerta già annullata non dà errore.
     *
     * @param alertId id dell'allerta
     * @param hikerId id dell'escursionista autenticato
     * @throws ResourceNotFoundException se l'allerta non esiste o appartiene a un altro escursionista
     * @throws BusinessRuleViolationException se l'allerta è già stata risolta
     */
    @Transactional
    public void cancelSos(UUID alertId, UUID hikerId) {
        SosAlert alert = sosAlertRepository.findByIdAndHikerIdForUpdate(alertId, hikerId)
                .orElseThrow(() -> new ResourceNotFoundException("SOS alert", alertId));
        lockAssignedTeam(alert);
        alert.cancel(clock.instant());
    }

    /**
     * Legge un'allerta per id. Gli operatori e gli amministratori possono leggerle tutte; l'escursionista solo
     * la propria (l'header Location della risposta al POST punta proprio qui). Il controllo avviene dopo la
     * lettura, con {@code @PostAuthorize}, confrontando {@code hikerId} con l'utente autenticato.
     *
     * @param alertId id dell'allerta
     * @return l'allerta con la squadra assegnata
     * @throws ResourceNotFoundException se l'allerta non esiste
     */
    @Transactional(readOnly = true)
    @PostAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN') or returnObject.hikerId().toString() == authentication.name")
    public SosAlertView getAlert(UUID alertId) {
        SosAlert alert = sosAlertRepository.findByIdWithDetails(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("SosAlert", alertId));
        return SosAlertView.from(alert, geometryMapper);
    }

    /**
     * Restituisce l'allerta aperta dell'escursionista. L'app la usa per ritrovare l'SOS in corso, per esempio
     * dopo un riavvio, e per seguirne lo stato.
     *
     * @param hikerId id dell'escursionista autenticato
     * @return l'allerta aperta
     * @throws ResourceNotFoundException se non c'è nessuna allerta aperta
     */
    @Transactional(readOnly = true)
    public SosAlertView getActiveAlertForHiker(UUID hikerId) {
        return sosAlertRepository.findFirstByHikerIdAndStatusIn(hikerId, ACTIVE_STATUSES)
                .map(alert -> SosAlertView.from(alert, geometryMapper))
                .orElseThrow(() -> new ResourceNotFoundException("Active SosAlert for hiker", hikerId));
    }

    /**
     * Elenco paginato delle allerte aperte, per la scheda degli operatori.
     *
     * @param pageable pagina e ordinamento richiesti
     * @return una pagina di allerte ACTIVE o DISPATCHED
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN')")
    public Page<SosAlertView> findActiveAlerts(Pageable pageable) {
        return sosAlertRepository.findByStatusIn(ACTIVE_STATUSES, pageable)
                .map(alert -> SosAlertView.from(alert, geometryMapper));
    }

    /**
     * Allerte aperte entro un raggio da un punto, dalla più vicina. Raggio e numero di risultati vengono
     * limitati qui (al massimo {@link #MAX_RADIUS_METERS} metri e 50 allerte) anche se il controller li
     * valida già.
     *
     * @param lon longitudine del centro
     * @param lat latitudine del centro
     * @param radiusMeters raggio in metri
     * @param limit numero massimo di allerte
     * @return le allerte trovate, ordinate per distanza geodetica
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN')")
    public List<SosAlertView> findActiveNearby(double lon, double lat, double radiusMeters, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        return sosAlertRepository.findNearbyActive(lon, lat, Math.min(radiusMeters, MAX_RADIUS_METERS), boundedLimit)
                .stream()
                .map(alert -> SosAlertView.from(alert, geometryMapper))
                .toList();
    }

    /**
     * Invia una squadra su un'allerta e avvisa l'escursionista con una push. I lock si prendono in ordine
     * fisso, prima l'allerta e poi la squadra, per non andare in deadlock con la chiusura o l'annullamento.
     *
     * @param operatorId operatore che prende in carico l'allerta
     * @param alertId id dell'allerta
     * @param cmd squadra da inviare
     * @return l'allerta aggiornata
     * @throws ResourceNotFoundException se allerta o squadra non esistono
     * @throws it.unical.geosentinel.shared.domain.exception.AlertAlreadyTakenException se un'altra squadra è
     *         già stata inviata
     * @throws BusinessRuleViolationException se l'allerta è chiusa o la squadra non è disponibile
     */
    @Transactional
    @PreAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN')")
    public SosAlertView dispatchTeam(UUID operatorId, UUID alertId, DispatchTeamCommand cmd) {
        SosAlert alert = sosAlertRepository.findByIdForUpdate(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("SosAlert", alertId));
        RescueTeam team = rescueTeamRepository.findByIdForUpdate(cmd.teamId())
                .orElseThrow(() -> new ResourceNotFoundException("RescueTeam", cmd.teamId()));

        alert.dispatch(team, operatorId);
        applicationEventPublisher.publishEvent(new SosAlertStatusChangedEvent(alert.getId(), alert.getHikerId(), alert.getStatus()));
        return SosAlertView.from(alert, geometryMapper);
    }

    /**
     * Chiude un'allerta come risolta, libera la squadra e avvisa l'escursionista con una push.
     *
     * @param alertId id dell'allerta
     * @return l'allerta chiusa
     * @throws ResourceNotFoundException se l'allerta non esiste
     * @throws BusinessRuleViolationException se l'allerta è già chiusa
     */
    @Transactional
    @PreAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN')")
    public SosAlertView resolveAlert(UUID alertId) {
        SosAlert alert = sosAlertRepository.findByIdForUpdate(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("SosAlert", alertId));
        lockAssignedTeam(alert);

        alert.resolve(clock.instant());
        applicationEventPublisher.publishEvent(new SosAlertStatusChangedEvent(alert.getId(), alert.getHikerId(), alert.getStatus()));
        return SosAlertView.from(alert, geometryMapper);
    }

    /**
     * Registra una nuova squadra di soccorso. L'anagrafica delle squadre la gestisce l'amministratore; gli
     * operatori si limitano a usarle.
     *
     * @param cmd dati della nuova squadra
     * @return la squadra salvata
     * @throws BusinessRuleViolationException se esiste già una squadra con lo stesso nome (senza distinguere
     *         maiuscole e minuscole)
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RescueTeamView createTeam(CreateRescueTeamCommand cmd){
        if(rescueTeamRepository.existsByNameIgnoreCase(cmd.name())){
            throw new BusinessRuleViolationException("A rescue team with name '" + cmd.name() + "' already exists");
        }

        RescueTeam team = new RescueTeam(
                cmd.name(),
                cmd.callSign(),
                cmd.baseLocation(),
                cmd.contactNumber()
        );
        return RescueTeamView.from(rescueTeamRepository.save(team), geometryMapper);
    }

    /**
     * Legge una squadra per id.
     *
     * @param teamId id della squadra
     * @return la squadra
     * @throws ResourceNotFoundException se la squadra non esiste
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN')")
    public RescueTeamView getTeam(UUID teamId) {
        return rescueTeamRepository.findById(teamId)
                .map(team -> RescueTeamView.from(team, geometryMapper))
                .orElseThrow(() -> new ResourceNotFoundException("RescueTeam", teamId));
    }

    /**
     * Squadre disponibili entro un raggio dalla loro base, dalla più vicina. Serve all'operatore per scegliere
     * chi inviare su un'allerta. Il numero di risultati è limitato a 30.
     *
     * @param lon longitudine del centro
     * @param lat latitudine del centro
     * @param radiusMeters raggio in metri
     * @param limit numero massimo di squadre
     * @return le squadre AVAILABLE trovate, ordinate per distanza
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN')")
    public List<RescueTeamView> findAvailableTeamsNearby(double lon, double lat, double radiusMeters, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 30));
        return rescueTeamRepository.findAvailableNearby(lon, lat, radiusMeters, boundedLimit)
                .stream()
                .map(team -> RescueTeamView.from(team, geometryMapper))
                .toList();
    }

    /**
     * Prende il lock sulla squadra assegnata all'allerta, se c'è. Chiudere o annullare un'allerta modifica
     * anche la squadra, quindi va bloccata pure lei, sempre dopo l'allerta. Chiamare getId() sul proxy lazy non
     * lo inizializza; è la query con il lock a caricare lo stato aggiornato della squadra nel contesto di
     * persistenza.
     *
     * @param alert allerta già bloccata
     */
    private void lockAssignedTeam(SosAlert alert) {
        if (alert.getAssignedTeam() != null) {
            rescueTeamRepository.findByIdForUpdate(alert.getAssignedTeam().getId());
        }
    }
}
