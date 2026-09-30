package it.unical.geosentinel.notification.infrastructure.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import it.unical.geosentinel.notification.application.port.PushNotifier;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// FCM consegna su Android e, tramite APNs (chiave caricata nella console Firebase), su iOS
@Component
@ConditionalOnProperty(name = "geosentinel.push.enabled", havingValue = "true")
public class FcmPushNotifier implements PushNotifier {
    // limite di FCM per sendEachForMulticast
    private static final int MAX_TOKENS_PER_CALL = 500;
    // token da cancellare. INVALID_ARGUMENT vale anche per un payload sbagliato, ma il nostro è fisso e piccolo
    private static final Set<MessagingErrorCode> INVALID_TOKEN_CODES = Set.of(
            MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT, MessagingErrorCode.SENDER_ID_MISMATCH);

    private final FirebaseApp app;
    private final FirebaseMessaging messaging;

    // Percorso del JSON dell'account di servizio, dal .env: il .env diventa property di Spring, non variabile
    // d'ambiente, quindi GoogleCredentials.getApplicationDefault() non lo vedrebbe
    public FcmPushNotifier(@Value("${geosentinel.push.credentials-file}") String credentialsFile) throws IOException {
        try (InputStream in = Files.newInputStream(Path.of(credentialsFile))) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            this.app = FirebaseApp.initializeApp(options, "geosentinel");
        }
        this.messaging = FirebaseMessaging.getInstance(app);
    }

    // l'app ha un nome fisso: senza delete un riavvio del contesto (devtools) fallirebbe con "already exists"
    @PreDestroy
    void close() {
        app.delete();
    }

    @Override
    public Result send(Collection<String> tokens, PushMessage message) {
        List<String> all = List.copyOf(tokens);
        int delivered = 0;
        Set<String> invalid = new HashSet<>();
        for (int from = 0; from < all.size(); from += MAX_TOKENS_PER_CALL) {
            List<String> chunk = all.subList(from, Math.min(from + MAX_TOKENS_PER_CALL, all.size()));
            BatchResponse batch = sendChunk(chunk, message);
            delivered += batch.getSuccessCount();
            List<SendResponse> responses = batch.getResponses();
            for (int i = 0; i < responses.size(); i++) {
                FirebaseMessagingException error = responses.get(i).getException();
                // il codice è null per gli errori che non vengono da FCM (credenziali, rete), e Set.of rifiuta null
                MessagingErrorCode code = error == null ? null : error.getMessagingErrorCode();
                if (code != null && INVALID_TOKEN_CODES.contains(code)) {
                    invalid.add(chunk.get(i));
                }
            }
        }
        return new Result(delivered, invalid);
    }

    private BatchResponse sendChunk(List<String> tokens, PushMessage message) {
        MulticastMessage multicast = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder().setTitle(message.title()).setBody(message.body()).build())
                .putAllData(message.data())
                // priorità alta: consegna immediata anche con il telefono in risparmio energetico
                .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
                .setApnsConfig(ApnsConfig.builder()
                        .putHeader("apns-priority", "10")
                        .setAps(Aps.builder().setSound("default").build())
                        .build())
                .build();
        try {
            return messaging.sendEachForMulticast(multicast);
        } catch (FirebaseMessagingException e) {
            throw new IllegalStateException("FCM multicast failed", e);
        }
    }
}
