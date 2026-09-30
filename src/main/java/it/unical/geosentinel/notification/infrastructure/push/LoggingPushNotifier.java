package it.unical.geosentinel.notification.infrastructure.push;

import it.unical.geosentinel.notification.application.port.PushNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;

// Senza progetto Firebase (local, test, CI): registra l'invio e lo considera consegnato. Niente token nei log
@Component
@ConditionalOnProperty(name = "geosentinel.push.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingPushNotifier implements PushNotifier {
    private static final Logger log = LoggerFactory.getLogger(LoggingPushNotifier.class);

    @Override
    public Result send(Collection<String> tokens, PushMessage message) {
        log.info("Push (disabled) to {} device(s): {}", tokens.size(), message.data());
        return new Result(tokens.size(), Set.of());
    }
}
