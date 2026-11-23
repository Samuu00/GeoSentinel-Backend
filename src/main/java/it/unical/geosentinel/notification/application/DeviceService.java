// Servizio applicativo delle notifiche push: registra e rimuove i token dei dispositivi degli utenti e
// invia le push attraverso la porta PushNotifier. È usato dalle altre parti del backend (per esempio dalle
// allerte SOS) che vogliono avvisare un gruppo di utenti.
package it.unical.geosentinel.notification.application;

import it.unical.geosentinel.notification.application.port.PushNotifier;
import it.unical.geosentinel.notification.application.port.PushNotifier.PushMessage;
import it.unical.geosentinel.notification.domain.DevicePlatform;
import it.unical.geosentinel.notification.infrastructure.persistence.DeviceTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Gestione dei dispositivi degli utenti e invio delle push. Il servizio non conosce i ruoli: sono i chiamanti
 * a scegliere i destinatari, passando gli id degli utenti.
 */
@Service
public class DeviceService {
    private final DeviceTokenRepository deviceTokenRepository;
    private final PushNotifier pushNotifier;
    private final Clock clock;

    /**
     * @param deviceTokenRepository repository dei token
     * @param pushNotifier implementazione dell'invio (FCM oppure solo log)
     * @param clock orologio iniettato, sostituibile nei test
     */
    public DeviceService(DeviceTokenRepository deviceTokenRepository, PushNotifier pushNotifier, Clock clock) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.pushNotifier = pushNotifier;
        this.clock = clock;
    }

    /**
     * Esito di un invio.
     *
     * @param devices numero di token a cui si è provato a inviare la push
     * @param delivered numero di token per cui il servizio di push ha accettato il messaggio
     */
    public record DeliveryReport(int devices, int delivered) {}

    /**
     * Registra il token di un dispositivo per l'utente, o aggiorna quello esistente. Se il token era di un altro
     * utente (stesso telefono, altro account) passa all'utente attuale.
     *
     * @param userId utente autenticato
     * @param token token del dispositivo
     * @param platform piattaforma del dispositivo
     */
    @Transactional
    public void register(UUID userId, String token, DevicePlatform platform) {
        deviceTokenRepository.upsert(userId, token, platform.name(), clock.instant());
    }

    /**
     * Rimuove il token dell'utente. L'operazione è idempotente: un token assente o appartenente a un altro
     * utente non produce errori, perché può capitare con un logout ripetuto o con un token già riassegnato.
     *
     * @param userId utente autenticato
     * @param token token da rimuovere
     */
    @Transactional
    public void unregister(UUID userId, String token) {
        deviceTokenRepository.deleteByUserIdAndToken(userId, token);
    }

    /**
     * Cancella tutti i token dell'utente. Si usa alla cancellazione dell'account: i token sono dati personali
     * e un account cancellato non deve più ricevere push.
     *
     * @param userId utente da ripulire
     */
    @Transactional
    public void deleteAllOf(UUID userId) {
        deviceTokenRepository.deleteAllByUserId(userId);
    }

    /**
     * @param userIds utenti di cui si vogliono i token
     * @return i token registrati per quegli utenti; lista vuota se non ne viene passato nessuno
     */
    @Transactional(readOnly = true)
    public List<String> tokensOf(Collection<UUID> userIds) {
        return userIds.isEmpty() ? List.of() : deviceTokenRepository.findTokensByUserIdIn(userIds);
    }

    /**
     * Invia una push a tutti i dispositivi degli utenti indicati e cancella i token che il servizio di push
     * segnala come non più validi. Il metodo non apre una transazione, perché l'invio è una chiamata di rete
     * e terrebbe occupata una connessione al database per tutta la sua durata.
     *
     * @param userIds destinatari
     * @param message titolo, testo e dati della push
     * @return quanti dispositivi c'erano e a quanti la push è stata consegnata
     * @throws RuntimeException se fallisce l'intero invio (rete, credenziali)
     */
    public DeliveryReport sendToUsers(Collection<UUID> userIds, PushMessage message) {
        List<String> tokens = tokensOf(userIds);
        if (tokens.isEmpty()) {
            return new DeliveryReport(0, 0);
        }
        PushNotifier.Result result = pushNotifier.send(tokens, message);
        if (!result.invalidTokens().isEmpty()) {
            deviceTokenRepository.deleteByTokenIn(result.invalidTokens());
        }
        return new DeliveryReport(tokens.size(), result.delivered());
    }
}
