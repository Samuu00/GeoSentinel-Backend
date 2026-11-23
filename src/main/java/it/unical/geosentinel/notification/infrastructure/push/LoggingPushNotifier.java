// Implementazione di PushNotifier che non invia nulla, usata quando le push sono disattivate.
package it.unical.geosentinel.notification.infrastructure.push;

import it.unical.geosentinel.notification.application.port.PushNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;

/**
 * Sostituto di FCM per gli ambienti senza progetto Firebase (sviluppo locale, test, CI). È attivo quando
 * geosentinel.push.enabled vale false o manca. Scrive nel log solo il numero di dispositivi e i dati del
 * messaggio, mai i token, e considera ogni invio consegnato.
 */
@Component
@ConditionalOnProperty(name = "geosentinel.push.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingPushNotifier implements PushNotifier {
    private static final Logger log = LoggerFactory.getLogger(LoggingPushNotifier.class);

    /**
     * @return un risultato con tutti i token consegnati e nessun token non valido
     */
    @Override
    public Result send(Collection<String> tokens, PushMessage message) {
        log.info("Push (disabled) to {} device(s): {}", tokens.size(), message.data());
        return new Result(tokens.size(), Set.of());
    }
}
