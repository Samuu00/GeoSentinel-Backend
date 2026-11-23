// Porta di uscita verso il servizio di notifiche push. Lo strato applicativo dipende solo da questa
// interfaccia; le implementazioni stanno in infrastructure/push.
package it.unical.geosentinel.notification.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Astrazione del servizio di push. Con {@code geosentinel.push.enabled=true} è implementata da {@code FcmPushNotifier}
 * (Firebase Cloud Messaging); altrimenti, come nei test e in CI, da {@code LoggingPushNotifier}, che scrive solo un log.
 */
public interface PushNotifier {

    /**
     * Invia lo stesso messaggio a tutti i token indicati.
     *
     * @param tokens token dei dispositivi destinatari
     * @param message messaggio da inviare
     * @return quante consegne sono riuscite e quali token non sono più validi
     * @throws RuntimeException se fallisce l'intero invio (rete, credenziali); gli errori dei singoli token non
     *         sono eccezioni ma finiscono nel risultato
     */
    Result send(Collection<String> tokens, PushMessage message);

    /**
     * Esito di un invio.
     *
     * @param delivered numero di consegne accettate
     * @param invalidTokens token che il servizio non riconosce più (app disinstallata, token scaduto) e che
     *        vanno cancellati; la collezione viene copiata in un insieme immutabile
     */
    record Result(int delivered, Set<String> invalidTokens) {
        public Result {
            invalidTokens = Set.copyOf(invalidTokens);
        }
    }

    /**
     * Messaggio di una push. Titolo e testo compaiono sulla schermata di blocco del telefono, quindi non devono
     * mai contenere posizione, note mediche o nomi; i dati contengono solo il tipo di evento e gli id.
     *
     * @param title titolo della notifica
     * @param body testo della notifica
     * @param data coppie chiave e valore lette dall'app; la mappa viene copiata in una immutabile
     */
    record PushMessage(String title, String body, Map<String, String> data) {
        public PushMessage {
            Objects.requireNonNull(title, "title cannot be null");
            Objects.requireNonNull(body, "body cannot be null");
            data = Map.copyOf(data);
        }
    }
}
