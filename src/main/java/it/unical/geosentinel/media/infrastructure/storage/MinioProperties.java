package it.unical.geosentinel.media.infrastructure.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// endpoint: raggiunto dall'app (es. http://minio:9000 nel compose); publicEndpoint: quello che vede il client,
// usato per firmare gli URL (la firma include l'host: un URL firmato con l'host interno non funziona fuori da Docker)
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
