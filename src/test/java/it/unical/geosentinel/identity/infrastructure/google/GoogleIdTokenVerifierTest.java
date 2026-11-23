// Test unitario di GoogleIdTokenVerifier, il componente che controlla l'ID token ricevuto dall'app dopo il
// login con Google. Il decoder JWT (firma e scadenza) è sostituito da un mock di Mockito, così il test può
// costruire token con i claim voluti e verificare i controlli aggiuntivi: emittente, destinatario, email e
// distinzione tra token non valido (401) e servizio di Google non raggiungibile (503).
package it.unical.geosentinel.identity.infrastructure.google;

import com.nimbusds.jose.RemoteKeySourceException;
import it.unical.geosentinel.shared.domain.exception.IdentityProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifica l'accettazione e il rifiuto degli ID token di Google in base ai loro claim.
 */
class GoogleIdTokenVerifierTest {

    /** Client id OAuth dell'app, l'unico destinatario ({@code aud}) accettato. */
    private static final String CLIENT_ID = "web-client.apps.googleusercontent.com";

    /** Decoder JWT finto, che restituisce il token preparato da {@link #decodes(Consumer)}. */
    private final JwtDecoder decoder = mock(JwtDecoder.class);

    /**
     * Verificatore sotto test. Tra i client id configurati c'è anche una stringa vuota, per controllare che
     * un valore vuoto nella configurazione non venga mai considerato valido.
     */
    private final GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(Set.of(CLIENT_ID, " "), decoder);

    /**
     * Configura il decoder finto in modo che restituisca un token valido di Google, modificato dalla funzione
     * indicata.
     *
     * @param customizer modifica da applicare al token di base, ad esempio per cambiare o togliere un claim
     */
    private void decodes(Consumer<Jwt.Builder> customizer) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256")
                .claim("iss", "https://accounts.google.com")
                .audience(List.of(CLIENT_ID))
                .subject("1234567890")
                .claim("email", "Mario@Example.com")
                .claim("email_verified", true)
                .claim("name", "Mario")
                .issuedAt(Instant.parse("2026-10-18T10:00:00Z"))
                .expiresAt(Instant.parse("2026-10-18T11:00:00Z"));
        customizer.accept(builder);
        when(decoder.decode(anyString())).thenReturn(builder.build());
    }

    /**
     * Google usa come emittente sia {@code https://accounts.google.com} sia {@code accounts.google.com}, senza
     * schema: entrambi devono essere accettati. La forma senza schema non è un URL valido e leggerla con
     * {@code Jwt.getIssuer()} provocherebbe un errore 500.
     *
     * @param issuer il valore del claim {@code iss}
     */
    @ParameterizedTest
    @ValueSource(strings = {"https://accounts.google.com", "accounts.google.com"})
    void acceptsBothGoogleIssuers(String issuer) {
        decodes(b -> b.claim("iss", issuer));

        var identity = verifier.verify("token");

        assertThat(identity.subject()).isEqualTo("1234567890");
        assertThat(identity.emailVerified()).isTrue();
    }

    /** Un token con un emittente diverso da Google deve essere rifiutato con {@link BadCredentialsException}. */
    @Test
    void rejectsForeignIssuer() {
        decodes(b -> b.claim("iss", "https://evil.example.com"));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(BadCredentialsException.class);
    }

    /**
     * Un token autentico di Google ma emesso per un'altra app (destinatario diverso) deve essere rifiutato:
     * altrimenti chiunque potrebbe riusare qui un token ottenuto da un'app qualsiasi.
     */
    @Test
    void rejectsForeignAudience() {
        decodes(b -> b.audience(List.of("other-app.apps.googleusercontent.com")));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(BadCredentialsException.class);
    }

    /** Un destinatario vuoto non deve coincidere con il client id vuoto presente nella configurazione. */
    @Test
    void blankClientIdNeverMatches() {
        decodes(b -> b.audience(List.of(" ")));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(BadCredentialsException.class);
    }

    /** Un token senza scadenza deve essere rifiutato. */
    @Test
    void rejectsTokenWithoutExpiration() {
        decodes(b -> b.claims(claims -> claims.remove("exp")));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(BadCredentialsException.class);
    }

    /** Un token senza email deve essere rifiutato, perché l'account si collega tramite l'email. */
    @Test
    void rejectsTokenWithoutEmail() {
        decodes(b -> b.claims(claims -> claims.remove("email")));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(BadCredentialsException.class);
    }

    /** Se manca il claim {@code email_verified}, il token è accettato ma l'email risulta non verificata. */
    @Test
    void unverifiedEmailIsReported() {
        decodes(b -> b.claims(claims -> claims.remove("email_verified")));
        assertThat(verifier.verify("token").emailVerified()).isFalse();
    }

    /**
     * Se le chiavi pubbliche di Google non si possono scaricare, l'errore è del servizio esterno e non del
     * client: si attende {@link IdentityProviderUnavailableException} (503) e non un 401.
     */
    @Test
    void unreachableKeysAre503NotA401() {
        when(decoder.decode(anyString())).thenThrow(
                new JwtException("Couldn't retrieve remote JWK set", new RemoteKeySourceException("timeout", null)));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(IdentityProviderUnavailableException.class);
    }

    /** Una firma non valida è un errore del token: si attende {@link BadCredentialsException} (401). */
    @Test
    void badSignatureIs401() {
        when(decoder.decode(anyString())).thenThrow(new JwtException("Invalid signature"));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(BadCredentialsException.class);
    }
}
