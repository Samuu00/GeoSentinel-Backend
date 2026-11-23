// Test unitario dell'entità PasswordResetToken, che rappresenta una richiesta di reimpostazione della
// password: contiene l'hash del codice inviato via email, la scadenza e il numero di tentativi sbagliati.
// Gli istanti sono passati come argomenti, quindi il test non dipende dall'orologio e non usa Spring.
package it.unical.geosentinel.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica scadenza, limite di tentativi falliti e consumo del token di reset della password.
 */
class PasswordResetTokenTest {

    /** Istante di riferimento in cui il token viene creato. */
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    /**
     * Crea un token per un escursionista qualsiasi, valido per 15 minuti a partire da {@link #NOW}.
     *
     * @return un token nuovo, non ancora usato
     */
    private static PasswordResetToken token() {
        User user = User.createHiker("hiker@geosentinel.dev", "{bcrypt}x", "Hiker");
        return new PasswordResetToken(user, "{bcrypt}hash", NOW.plus(Duration.ofMinutes(15)), NOW);
    }

    /**
     * Il token è utilizzabile alla creazione e dopo 14 minuti, ma non più allo scadere esatto dei 15 minuti:
     * la scadenza è esclusiva.
     */
    @Test
    void usableUntilExpiry() {
        PasswordResetToken token = token();
        assertThat(token.isUsable(NOW)).isTrue();
        assertThat(token.isUsable(NOW.plus(Duration.ofMinutes(14)))).isTrue();
        assertThat(token.isUsable(NOW.plus(Duration.ofMinutes(15)))).isFalse();
    }

    /**
     * Con un massimo di 5 tentativi, dopo 4 errori il token è ancora valido; al quinto viene consumato e non è
     * più utilizzabile. Ulteriori errori dopo il consumo non incrementano più il contatore.
     */
    @Test
    void consumedAfterMaxFailures() {
        PasswordResetToken token = token();
        for (int i = 0; i < 4; i++) {
            token.registerFailure(NOW, 5);
        }
        assertThat(token.isUsable(NOW)).isTrue();
        assertThat(token.getFailedAttempts()).isEqualTo(4);

        token.registerFailure(NOW, 5);
        assertThat(token.isConsumed()).isTrue();
        assertThat(token.isUsable(NOW)).isFalse();
        // Un token già consumato ignora i tentativi successivi: il contatore resta a 5.
        token.registerFailure(NOW, 5);
        assertThat(token.getFailedAttempts()).isEqualTo(5);
    }

    /**
     * Consumare due volte lo stesso token non sovrascrive l'istante di consumo: resta quello della prima volta.
     */
    @Test
    void consumeKeepsTheFirstInstant() {
        PasswordResetToken token = token();
        token.consume(NOW);
        token.consume(NOW.plusSeconds(60));
        assertThat(token.getConsumedAt()).isEqualTo(NOW);
    }
}
