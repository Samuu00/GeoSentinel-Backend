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

class GoogleIdTokenVerifierTest {
    private static final String CLIENT_ID = "web-client.apps.googleusercontent.com";

    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(Set.of(CLIENT_ID, " "), decoder);

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

    // Google emette anche l'issuer senza schema: Jwt.getIssuer() lo avrebbe trasformato in un 500
    @ParameterizedTest
    @ValueSource(strings = {"https://accounts.google.com", "accounts.google.com"})
    void acceptsBothGoogleIssuers(String issuer) {
        decodes(b -> b.claim("iss", issuer));

        var identity = verifier.verify("token");

        assertThat(identity.subject()).isEqualTo("1234567890");
        assertThat(identity.emailVerified()).isTrue();
    }

    @Test
    void rejectsForeignIssuer() {
        decodes(b -> b.claim("iss", "https://evil.example.com"));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(BadCredentialsException.class);
    }

    // Token valido di Google ma emesso per un'altra app
    @Test
    void rejectsForeignAudience() {
        decodes(b -> b.audience(List.of("other-app.apps.googleusercontent.com")));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void blankClientIdNeverMatches() {
        decodes(b -> b.audience(List.of(" ")));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rejectsTokenWithoutExpiration() {
        decodes(b -> b.claims(claims -> claims.remove("exp")));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rejectsTokenWithoutEmail() {
        decodes(b -> b.claims(claims -> claims.remove("email")));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void unverifiedEmailIsReported() {
        decodes(b -> b.claims(claims -> claims.remove("email_verified")));
        assertThat(verifier.verify("token").emailVerified()).isFalse();
    }

    @Test
    void unreachableKeysAre503NotA401() {
        when(decoder.decode(anyString())).thenThrow(
                new JwtException("Couldn't retrieve remote JWK set", new RemoteKeySourceException("timeout", null)));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(IdentityProviderUnavailableException.class);
    }

    @Test
    void badSignatureIs401() {
        when(decoder.decode(anyString())).thenThrow(new JwtException("Invalid signature"));
        assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(BadCredentialsException.class);
    }
}
