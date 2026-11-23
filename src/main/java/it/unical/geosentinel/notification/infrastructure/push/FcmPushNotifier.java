// Implementazione di PushNotifier basata su Firebase Cloud Messaging (FCM), attiva solo quando la property
// geosentinel.push.enabled vale true. FCM consegna direttamente su Android e, tramite APNs (la chiave di
// Apple è caricata nella console di Firebase), anche su iOS.
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

/**
 * Invio delle push con l'SDK Firebase Admin. Divide i destinatari in blocchi da {@link #MAX_TOKENS_PER_CALL},
 * somma le consegne riuscite e raccoglie i token che FCM dichiara non validi, così che il chiamante li possa
 * cancellare.
 */
@Component
@ConditionalOnProperty(name = "geosentinel.push.enabled", havingValue = "true")
public class FcmPushNotifier implements PushNotifier {
    /** Numero massimo di token accettato da FCM in una chiamata a sendEachForMulticast. */
    private static final int MAX_TOKENS_PER_CALL = 500;
    /**
     * Codici di errore per cui il token va considerato non valido e cancellato. INVALID_ARGUMENT potrebbe
     * indicare anche un payload sbagliato, ma il nostro ha una forma fissa e piccola, quindi qui significa un
     * token malformato.
     */
    private static final Set<MessagingErrorCode> INVALID_TOKEN_CODES = Set.of(
            MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT, MessagingErrorCode.SENDER_ID_MISMATCH);

    private final FirebaseApp app;
    private final FirebaseMessaging messaging;

    /**
     * Inizializza un'istanza di FirebaseApp con le credenziali dell'account di servizio.
     * Il percorso del file JSON arriva dal .env, che Spring importa come property e non come variabile
     * d'ambiente: per questo le credenziali si leggono esplicitamente dal file invece di usare
     * GoogleCredentials.getApplicationDefault(), che non lo troverebbe.
     *
     * @param credentialsFile percorso del JSON dell'account di servizio Firebase
     * @throws IOException se il file non si può leggere
     */
    public FcmPushNotifier(@Value("${geosentinel.push.credentials-file}") String credentialsFile) throws IOException {
        try (InputStream in = Files.newInputStream(Path.of(credentialsFile))) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            this.app = FirebaseApp.initializeApp(options, "geosentinel");
        }
        this.messaging = FirebaseMessaging.getInstance(app);
    }

    /**
     * Elimina l'istanza di FirebaseApp alla chiusura del contesto Spring. L'istanza ha un nome fisso: senza
     * questa pulizia un riavvio del contesto (per esempio con devtools) fallirebbe perché il nome esiste già.
     */
    @PreDestroy
    void close() {
        app.delete();
    }

    /**
     * Invia il messaggio a blocchi di al massimo {@link #MAX_TOKENS_PER_CALL} token.
     *
     * @param tokens destinatari
     * @param message messaggio da inviare
     * @return consegne riuscite e token non più validi
     * @throws IllegalStateException se la chiamata a FCM fallisce per intero
     */
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
                // Il codice è null per gli errori che non vengono da FCM (credenziali, rete) e un Set creato con
                // Set.of lancia un'eccezione se gli si chiede se contiene null: per questo il controllo esplicito.
                MessagingErrorCode code = error == null ? null : error.getMessagingErrorCode();
                if (code != null && INVALID_TOKEN_CODES.contains(code)) {
                    invalid.add(chunk.get(i));
                }
            }
        }
        return new Result(delivered, invalid);
    }

    /**
     * Costruisce e invia un messaggio multicast per un blocco di token.
     *
     * @param tokens blocco di token, al massimo {@link #MAX_TOKENS_PER_CALL}
     * @param message messaggio da inviare
     * @return la risposta di FCM, con un esito per ogni token
     * @throws IllegalStateException se FCM rifiuta l'intera richiesta
     */
    private BatchResponse sendChunk(List<String> tokens, PushMessage message) {
        MulticastMessage multicast = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder().setTitle(message.title()).setBody(message.body()).build())
                .putAllData(message.data())
                // Priorità alta su Android e "10" su APNs: la consegna è immediata anche con il telefono in
                // risparmio energetico, cosa necessaria per le allerte SOS.
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
