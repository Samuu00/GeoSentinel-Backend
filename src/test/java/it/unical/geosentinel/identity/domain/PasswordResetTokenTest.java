package it.unical.geosentinel.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetTokenTest {
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    private static PasswordResetToken token() {
        User user = User.createHiker("hiker@geosentinel.dev", "{bcrypt}x", "Hiker");
        return new PasswordResetToken(user, "{bcrypt}hash", NOW.plus(Duration.ofMinutes(15)), NOW);
    }

    @Test
    void usableUntilExpiry() {
        PasswordResetToken token = token();
        assertThat(token.isUsable(NOW)).isTrue();
        assertThat(token.isUsable(NOW.plus(Duration.ofMinutes(14)))).isTrue();
        assertThat(token.isUsable(NOW.plus(Duration.ofMinutes(15)))).isFalse();
    }

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
        // un errore dopo la consumazione non conta più
        token.registerFailure(NOW, 5);
        assertThat(token.getFailedAttempts()).isEqualTo(5);
    }

    @Test
    void consumeKeepsTheFirstInstant() {
        PasswordResetToken token = token();
        token.consume(NOW);
        token.consume(NOW.plusSeconds(60));
        assertThat(token.getConsumedAt()).isEqualTo(NOW);
    }
}
