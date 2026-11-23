// Proprietà di connessione a MinIO, lette dal prefisso geosentinel.storage.minio e validate all'avvio:
// se ne manca una l'applicazione non parte.
package it.unical.geosentinel.media.infrastructure.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Impostazioni di MinIO.
 *
 * @param endpoint       indirizzo usato dal backend, ad esempio http://minio:9000 nella rete di Docker Compose
 * @param publicEndpoint indirizzo visto dal client, usato per firmare gli URL: la firma include l'host, e un
 *                       URL firmato con l'host interno non funzionerebbe fuori da Docker
 * @param accessKey      chiave di accesso
 * @param secretKey      chiave segreta
 * @param bucket         nome del bucket privato
 */
@Validated
@ConfigurationProperties(prefix = "geosentinel.storage.minio")
public record MinioProperties(
        @NotBlank(message = "MinIO endpoint is required")
        String endpoint,
        @NotBlank(message = "MinIO public endpoint is required")
        String publicEndpoint,
        @NotBlank(message = "MinIO accessKey is required")
        String accessKey,
        @NotBlank(message = "MinIO secretKey is required")
        String secretKey,
        @NotBlank(message = "MinIO bucket is required")
        String bucket
) {}
