// Configurazione dello storage MinIO: abilita la lettura delle proprietà di connessione. Il bucket non
// viene creato qui ma dal servizio minio-init di Docker Compose, come bucket privato, così l'avvio
// dell'applicazione non fa chiamate di rete.
package it.unical.geosentinel.media.infrastructure.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registra {@link MinioProperties}.
 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {}
