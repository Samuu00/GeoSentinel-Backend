package it.unical.geosentinel.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Spento nei test (application-test.properties): i job girerebbero da soli durante gli IT, su dati di altri test.
// I test che li riguardano chiamano il metodo del job direttamente
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "geosentinel.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
