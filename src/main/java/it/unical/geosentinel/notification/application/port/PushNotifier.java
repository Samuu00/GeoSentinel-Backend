package it.unical.geosentinel.notification.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Porta verso il servizio di push (FCM in produzione, log in local/test)
public interface PushNotifier {

    // Un errore dell'intero invio (rete, credenziali) è un'eccezione; gli errori dei singoli token finiscono nel Result
    Result send(Collection<String> tokens, PushMessage message);

    // invalidTokens: token che FCM non riconosce più (app disinstallata, token scaduto), da cancellare
    record Result(int delivered, Set<String> invalidTokens) {
        public Result {
            invalidTokens = Set.copyOf(invalidTokens);
        }
    }

    // title e body compaiono sulla schermata di blocco: mai posizione, note mediche o nomi. data solo id e tipo evento
    record PushMessage(String title, String body, Map<String, String> data) {
        public PushMessage {
            Objects.requireNonNull(title, "title cannot be null");
            Objects.requireNonNull(body, "body cannot be null");
            data = Map.copyOf(data);
        }
    }
}
