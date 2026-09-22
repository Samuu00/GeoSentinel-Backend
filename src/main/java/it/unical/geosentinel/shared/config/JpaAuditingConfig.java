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

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "dateTimeProvider")
public class JpaAuditingConfig {
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

    @Bean
    public DateTimeProvider dateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }
}
