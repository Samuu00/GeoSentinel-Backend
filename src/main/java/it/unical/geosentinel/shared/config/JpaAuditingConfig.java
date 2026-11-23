// Configurazione dell'auditing JPA di Spring Data: compila in automatico i campi created_at, created_by,
// updated_at e updated_by di AuditableEntity. L'autore è l'utente autenticato, ricavato dal subject del
// JWT; la data viene dal bean Clock, così nei test si può controllare.
package it.unical.geosentinel.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.Clock;
import java.util.UUID;
import java.util.Optional;

/**
 * Abilita l'auditing JPA e definisce chi e quando ha creato o modificato un'entità.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "dateTimeProvider")
public class JpaAuditingConfig {
    /**
     * Fornisce l'id dell'utente corrente per i campi {@code created_by} e {@code updated_by}.
     * Restituisce un valore vuoto se non c'è un utente autenticato (ad esempio nei job pianificati o
     * durante la registrazione), se il principal non è un JWT o se il subject non è un UUID.
     *
     * @return il fornitore dell'autore delle modifiche
     */
    @Bean
    public AuditorAware<UUID> auditorAware() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()){
                return Optional.empty();
            }

            Object principal = authentication.getPrincipal();
            if(principal instanceof Jwt jwt){
                try{
                    return Optional.of(UUID.fromString(jwt.getSubject()));
                } catch (IllegalArgumentException e){
                    return Optional.empty();
                }
            }
            return Optional.empty();
        };
    }

    /**
     * Fornisce l'istante corrente per {@code created_at} e {@code updated_at} leggendolo dal bean Clock.
     *
     * @param clock orologio dell'applicazione
     * @return il fornitore della data delle modifiche
     */
    @Bean
    public DateTimeProvider dateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }
}
