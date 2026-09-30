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

// Dispositivi degli utenti e invio delle push. Non conosce i ruoli: i destinatari (id utente) li sceglie il chiamante
@Service
public class DeviceService {
    private final DeviceTokenRepository deviceTokenRepository;
    private final PushNotifier pushNotifier;
    private final Clock clock;

    public DeviceService(DeviceTokenRepository deviceTokenRepository, PushNotifier pushNotifier, Clock clock) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.pushNotifier = pushNotifier;
        this.clock = clock;
    }

    // devices = token a cui si è provato a inviare, delivered = quelli che FCM ha accettato
    public record DeliveryReport(int devices, int delivered) {}

    @Transactional
    public void register(UUID userId, String token, DevicePlatform platform) {
        deviceTokenRepository.upsert(userId, token, platform.name(), clock.instant());
    }

    // idempotente: un token assente o di un altro utente non dà errore (logout ripetuto, token già riassegnato)
    @Transactional
    public void unregister(UUID userId, String token) {
        deviceTokenRepository.deleteByUserIdAndToken(userId, token);
    }

    // cancellazione dell'account (M7.2): i token sono dati personali, e un account cancellato non riceve più push
    @Transactional
    public void deleteAllOf(UUID userId) {
        deviceTokenRepository.deleteAllByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<String> tokensOf(Collection<UUID> userIds) {
        return userIds.isEmpty() ? List.of() : deviceTokenRepository.findTokensByUserIdIn(userIds);
    }

    // Senza transazione: l'invio è una chiamata di rete. Un errore dell'intero invio arriva al chiamante come eccezione
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
