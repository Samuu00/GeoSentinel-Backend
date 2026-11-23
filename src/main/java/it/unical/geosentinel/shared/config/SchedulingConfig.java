// Configurazione che attiva i job pianificati (@Scheduled), ad esempio la pulizia dei media mai caricati.
// Si può spegnere con la proprietà geosentinel.scheduling.enabled=false: lo fa il profilo di test, perché
// durante i test di integrazione i job girerebbero da soli su dati che appartengono ad altri test. I test
// che riguardano un job chiamano direttamente il suo metodo.
package it.unical.geosentinel.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Abilita lo scheduling di Spring, attivo di default se la proprietà non è impostata.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "geosentinel.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
