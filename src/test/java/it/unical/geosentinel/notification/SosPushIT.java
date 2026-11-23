// Test di integrazione delle notifiche push (Firebase Cloud Messaging) legate agli SOS, con chiamate HTTP
// vere e PostGIS e Redis in Testcontainers. Il PushNotifier reale è sostituito da uno finto che registra i
// messaggi inviati e può simulare errori. Si controllano: registrazione dei dispositivi, destinatari scelti in
// base al ruolo, messaggi senza dati personali, recupero dopo un invio fallito, cancellazione dei token
// rifiutati da FCM e dei dispositivi di un account cancellato.
package it.unical.geosentinel.notification;

import it.unical.geosentinel.emergency.infrastructure.notification.SosNotificationRecovery;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.notification.application.port.PushNotifier;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica l'intero percorso delle notifiche push degli SOS, dalla registrazione del dispositivo all'invio.
 */
@Import(SosPushIT.FakePushConfig.class)
class SosPushIT extends AbstractIntegrationTest {

    /** Notificatore finto, che sostituisce quello reale verso FCM. */
    @Autowired
    private FakePushNotifier push;

    /** Job di recupero delle notifiche perse, chiamato a mano perché nei test i job pianificati sono spenti. */
    @Autowired
    private SosNotificationRecovery recovery;

    /** Svuota i messaggi registrati e azzera gli errori simulati prima di ogni test. */
    @BeforeEach
    void resetPush() {
        push.reset();
    }

    /**
     * Lo stesso token del telefono registrato prima da un utente e poi da un altro passa al secondo (con la
     * piattaforma aggiornata) invece di dare 409. Il primo utente non può più toglierlo: la sua richiesta dà
     * comunque 204 ma non ha effetto. Il secondo utente invece può toglierlo.
     */
    @Test
    void deviceTokenIsRegisteredMovedBetweenUsersAndUnregisteredOnlyByItsOwner() {
        String first = obtainToken(Role.HIKER);
        String second = obtainToken(Role.HIKER);

        assertThat(registerDevice(first, "shared-phone-token", "ANDROID").status()).isEqualTo(204);
        // Stesso telefono, altro account: il token viene spostato al secondo utente.
        assertThat(registerDevice(second, "shared-phone-token", "IOS").status()).isEqualTo(204);
        assertThat(deviceCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT platform FROM device_tokens", String.class)).isEqualTo("IOS");

        // La rimozione è idempotente: 204 anche quando il token non appartiene a chi chiede, senza effetti.
        assertThat(call(HttpMethod.DELETE, "/api/v1/devices/shared-phone-token", first, null).status()).isEqualTo(204);
        assertThat(deviceCount()).isEqualTo(1);
        assertThat(call(HttpMethod.DELETE, "/api/v1/devices/shared-phone-token", second, null).status()).isEqualTo(204);
        assertThat(deviceCount()).isZero();
    }

    /**
     * Piattaforma sconosciuta, token mancante o più lungo di 1024 caratteri devono dare 400; una
     * registrazione senza autenticazione deve dare 401.
     */
    @Test
    void invalidDeviceRegistrationIsRejected() {
        String token = obtainToken(Role.HIKER);
        assertThat(registerDevice(token, "some-token", "WINDOWS_PHONE").status()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/api/v1/devices", token, "{\"platform\":\"ANDROID\"}").status()).isEqualTo(400);
        assertThat(registerDevice(token, "x".repeat(1025), "ANDROID").status()).isEqualTo(400);
        assertThat(registerDevice(null, "some-token", "ANDROID").status()).isEqualTo(401);
    }

    /**
     * Un nuovo SOS deve essere notificato con un solo invio ai soli operatori di soccorso attivi: non a quelli
     * disattivati né agli escursionisti. Il messaggio contiene solo tipo e id dell'allerta; titolo e testo non
     * devono riportare note mediche né coordinate.
     */
    @Test
    void raisedSosIsPushedOnlyToActiveOperatorsWithoutPersonalData() throws InterruptedException {
        registerDevice(tokenFor(createUser(Role.RESCUE_OPERATOR)), "operator-phone", "ANDROID");
        User disabled = createUser(Role.RESCUE_OPERATOR);
        registerDevice(tokenFor(disabled), "disabled-operator-phone", "IOS");
        jdbcTemplate.update("UPDATE users SET enabled = false WHERE id = ?", disabled.getId());
        registerDevice(tokenFor(createUser(Role.HIKER)), "other-hiker-phone", "ANDROID");

        HttpResult raised = call(HttpMethod.POST, "/api/v1/sos", obtainToken(Role.HIKER), """
                {"location":{"lat":39.3050,"lon":16.5020},"medicalNotes":"diabetic"}
                """);
        assertThat(raised.status()).isEqualTo(201);
        String alertId = json(raised.body()).get("id").asString();

        awaitTrue(() -> notifiedAt(UUID.fromString(alertId)) != null);
        assertThat(push.sent).hasSize(1);
        FakePushNotifier.Sent sent = push.sent.getFirst();
        assertThat(sent.tokens()).containsExactly("operator-phone");
        assertThat(sent.message().data()).isEqualTo(Map.of("type", "SOS_RAISED", "alertId", alertId));
        assertThat(sent.message().title() + sent.message().body()).doesNotContain("diabetic", "39.3", "16.5");
    }

    /**
     * Cinque cicli di SOS lanciato e subito annullato creano sempre l'allerta, ma gli operatori ricevono al
     * massimo 3 push ogni 15 minuti, così un utente non può tempestarli di notifiche. Le allerte non notificate
     * per questo limite risultano comunque segnate come notificate, perché il job di recupero non le rilanci.
     */
    @Test
    void raiseCancelCyclesPushOperatorsAtMostThreeTimes() throws InterruptedException {
        registerDevice(tokenFor(createUser(Role.RESCUE_OPERATOR)), "operator-phone", "ANDROID");
        String hiker = obtainToken(Role.HIKER);

        List<UUID> alerts = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            HttpResult raised = call(HttpMethod.POST, "/api/v1/sos", hiker, """
                    {"location":{"lat":39.3050,"lon":16.5020}}
                    """);
            assertThat(raised.status()).isEqualTo(201);
            UUID alertId = UUID.fromString(json(raised.body()).get("id").asString());
            alerts.add(alertId);
            assertThat(call(HttpMethod.DELETE, "/api/v1/sos/" + alertId, hiker, null).status()).isEqualTo(204);
        }

        awaitTrue(() -> alerts.stream().allMatch(id -> notifiedAt(id) != null));
        assertThat(pushedTypes("operator-phone")).hasSize(3);
    }

    /**
     * Se l'invio fallisce, l'allerta resta non notificata; al giro successivo del job di recupero, con FCM di
     * nuovo disponibile, viene notificata con un solo invio.
     */
    @Test
    void failedPushLeavesAlertUnnotifiedAndRecoveryDeliversItLater() {
        registerDevice(tokenFor(createUser(Role.RESCUE_OPERATOR)), "operator-phone", "ANDROID");
        UUID alertId = insertUnnotifiedAlert();

        push.failing = true;
        recovery.redeliverMissed();
        assertThat(notifiedAt(alertId)).isNull();

        push.failing = false;
        recovery.redeliverMissed();
        assertThat(notifiedAt(alertId)).isNotNull();
        assertThat(push.sent).hasSize(1);
    }

    /**
     * I token che FCM dichiara non più validi (per esempio app disinstallata) devono essere cancellati, mentre
     * l'allerta risulta notificata perché almeno un dispositivo l'ha ricevuta.
     */
    @Test
    void tokensRejectedByFcmAreDeleted() {
        registerDevice(tokenFor(createUser(Role.RESCUE_OPERATOR)), "operator-phone", "ANDROID");
        registerDevice(tokenFor(createUser(Role.RESCUE_OPERATOR)), "uninstalled-app", "IOS");
        push.invalidTokens.add("uninstalled-app");
        UUID alertId = insertUnnotifiedAlert();

        recovery.redeliverMissed();

        assertThat(notifiedAt(alertId)).isNotNull();
        assertThat(jdbcTemplate.queryForList("SELECT token FROM device_tokens", String.class))
                .containsExactly("operator-phone");
    }

    /**
     * Se nessun dispositivo ha ricevuto l'allerta, questa resta da notificare e il token rifiutato viene
     * cancellato. Al giro successivo non c'è più nessun dispositivo a cui inviarla: l'allerta viene segnata
     * come notificata, per non riprovare all'infinito (gli operatori la vedono comunque nella loro lista).
     */
    @Test
    void alertIsRetriedWhenNoDeviceReceivedItButNotForeverWhenNoDeviceIsLeft() {
        registerDevice(tokenFor(createUser(Role.RESCUE_OPERATOR)), "uninstalled-app", "IOS");
        push.invalidTokens.add("uninstalled-app");
        UUID alertId = insertUnnotifiedAlert();

        recovery.redeliverMissed();
        assertThat(notifiedAt(alertId)).isNull();
        assertThat(deviceCount()).isZero();

        recovery.redeliverMissed();
        assertThat(notifiedAt(alertId)).isNotNull();
    }

    /**
     * L'escursionista che ha lanciato l'SOS deve ricevere una push quando gli viene inviata una squadra e una
     * quando l'allerta è risolta. Ogni messaggio contiene solo il tipo e l'id dell'allerta.
     */
    @Test
    void hikerIsPushedWhenTeamIsDispatchedAndWhenAlertIsResolved() throws InterruptedException {
        String hiker = obtainToken(Role.HIKER);
        registerDevice(hiker, "hiker-phone", "ANDROID");
        String operator = obtainToken(Role.RESCUE_OPERATOR);
        String alertId = json(call(HttpMethod.POST, "/api/v1/sos", hiker, """
                {"location":{"lat":39.3050,"lon":16.5020}}
                """).body()).get("id").asString();
        UUID teamId = insertTeam("Alpha");

        assertThat(call(HttpMethod.PATCH, "/api/v1/sos/" + alertId + "/dispatch", operator,
                "{\"teamId\":\"%s\"}".formatted(teamId)).status()).isEqualTo(200);
        awaitTrue(() -> pushedTypes("hiker-phone").contains("SOS_DISPATCHED"));

        assertThat(call(HttpMethod.PATCH, "/api/v1/sos/" + alertId + "/resolve", operator, null).status()).isEqualTo(200);
        awaitTrue(() -> pushedTypes("hiker-phone").contains("SOS_RESOLVED"));

        assertThat(push.sent).filteredOn(s -> s.tokens().contains("hiker-phone"))
                .allSatisfy(s -> assertThat(s.message().data()).containsEntry("alertId", alertId).hasSize(2));
    }

    /** La cancellazione dell'account elimina anche i dispositivi registrati dall'utente. */
    @Test
    void deletedAccountLosesItsDevices() {
        String hiker = obtainToken(Role.HIKER);
        registerDevice(hiker, "hiker-phone", "ANDROID");

        assertThat(call(HttpMethod.DELETE, "/api/v1/users/me", hiker, null).status()).isEqualTo(204);
        assertThat(deviceCount()).isZero();
    }

    /**
     * Registra un dispositivo per le notifiche push.
     *
     * @param accessToken l'access token dell'utente, oppure {@code null} per una chiamata anonima
     * @param deviceToken il token FCM del dispositivo
     * @param platform    la piattaforma, ad esempio {@code ANDROID} o {@code IOS}
     * @return la risposta del server
     */
    private HttpResult registerDevice(String accessToken, String deviceToken, String platform) {
        return call(HttpMethod.POST, "/api/v1/devices", accessToken,
                "{\"token\":\"%s\",\"platform\":\"%s\"}".formatted(deviceToken, platform));
    }

    /**
     * Inserisce un'allerta attiva creata due minuti fa e mai notificata, che il job di recupero considera persa.
     *
     * @return l'id dell'allerta
     */
    private UUID insertUnnotifiedAlert() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO sos_alerts (id, hiker_id, location, location_updated_at, status, created_at)
                VALUES (gen_random_uuid(), ?, ST_SetSRID(ST_MakePoint(16.50, 39.30), 4326), now(), 'ACTIVE',
                        now() - interval '2 minutes')
                RETURNING id
                """, UUID.class, createUser(Role.HIKER).getId());
    }

    /**
     * Legge l'istante di notifica di un'allerta.
     *
     * @param alertId l'id dell'allerta
     * @return il valore di {@code notified_at}, oppure {@code null} se non è ancora stata notificata
     */
    private Timestamp notifiedAt(UUID alertId) {
        return jdbcTemplate.queryForObject("SELECT notified_at FROM sos_alerts WHERE id = ?", Timestamp.class, alertId);
    }

    /**
     * Conta i dispositivi registrati.
     *
     * @return il numero di righe in {@code device_tokens}
     */
    private int deviceCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM device_tokens", Integer.class);
    }

    /**
     * Elenca i tipi dei messaggi inviati a un dispositivo.
     *
     * @param deviceToken il token del dispositivo
     * @return i valori del campo {@code type}, nell'ordine di invio
     */
    private List<String> pushedTypes(String deviceToken) {
        return push.sent.stream()
                .filter(s -> s.tokens().contains(deviceToken))
                .map(s -> s.message().data().get("type"))
                .toList();
    }

    /**
     * Aspetta che una condizione diventi vera, controllandola ogni 100 ms per al massimo 10 secondi. Serve
     * perché i listener delle notifiche girano su thread separati (pool sos-notify).
     *
     * @param condition la condizione da attendere
     * @throws InterruptedException se il thread viene interrotto durante l'attesa
     */
    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /**
     * Configurazione di test che registra {@link FakePushNotifier} come bean principale, al posto del
     * notificatore reale.
     */
    @TestConfiguration
    static class FakePushConfig {

        /**
         * Crea il notificatore finto.
         *
         * @return una nuova istanza, condivisa da tutti i test della classe
         */
        @Bean
        @Primary
        FakePushNotifier fakePushNotifier() {
            return new FakePushNotifier();
        }
    }

    /**
     * Implementazione finta di {@link PushNotifier}: registra ogni invio, può fallire a comando e può
     * dichiarare non validi alcuni token, come farebbe FCM. Le collezioni sono thread-safe perché gli invii
     * arrivano dai thread dei listener asincroni.
     */
    static class FakePushNotifier implements PushNotifier {

        /**
         * Un invio registrato.
         *
         * @param tokens  i token dei dispositivi destinatari
         * @param message il messaggio inviato
         */
        record Sent(List<String> tokens, PushMessage message) {}

        /** Invii effettuati, nell'ordine in cui sono avvenuti. */
        final List<Sent> sent = new CopyOnWriteArrayList<>();

        /** Token che il finto FCM rifiuta come non più validi. */
        final Set<String> invalidTokens = ConcurrentHashMap.newKeySet();

        /** Se {@code true}, ogni invio lancia un'eccezione, come se FCM non fosse raggiungibile. */
        volatile boolean failing;

        /** Riporta il notificatore allo stato iniziale. */
        void reset() {
            sent.clear();
            invalidTokens.clear();
            failing = false;
        }

        /**
         * Registra l'invio e restituisce quanti dispositivi lo hanno ricevuto e quali token sono stati rifiutati.
         *
         * @throws IllegalStateException se {@link #failing} è attivo
         */
        @Override
        public Result send(Collection<String> tokens, PushMessage message) {
            if (failing) {
                throw new IllegalStateException("FCM unavailable (test)");
            }
            sent.add(new Sent(List.copyOf(tokens), message));
            Set<String> rejected = new HashSet<>(tokens);
            rejected.retainAll(invalidTokens);
            return new Result(tokens.size() - rejected.size(), rejected);
        }
    }
}
