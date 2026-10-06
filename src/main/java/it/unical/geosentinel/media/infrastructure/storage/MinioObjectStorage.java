package it.unical.geosentinel.media.infrastructure.storage;

import io.minio.CopyObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MinioClient;
import io.minio.PostPolicy;
import io.minio.RemoveObjectArgs;
import io.minio.SourceObject;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import it.unical.geosentinel.media.application.port.ObjectStorage;
import it.unical.geosentinel.shared.domain.exception.StorageUnavailableException;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class MinioObjectStorage implements ObjectStorage {
    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);
    // Region fissa: senza, il client la chiede al server prima di firmare (una chiamata di rete verso il publicEndpoint)
    private static final String REGION = "us-east-1";
    // Gli upload del client finiscono qui; ciò che resta (policy riusate dopo il confirm) lo cancella la regola di
    // lifecycle del bucket (minio-init), non l'app
    static final String STAGING_PREFIX = "staging/";

    private final MinioClient minioClient;
    private final MinioClient signingClient;
    private final String bucket;
    private final String uploadUrl;
    private final Clock clock;

    public MinioObjectStorage(MinioProperties properties, Clock clock) {
        this.clock = clock;
        this.uploadUrl = properties.publicEndpoint().replaceAll("/+$", "") + "/" + properties.bucket();
        // Timeout brevi: le chiamate avvengono dentro transazioni che tengono una connessione del pool (default MinIO: 5 minuti)
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
        this.minioClient = MinioClient.builder()
                .endpoint(properties.endpoint())
                .region(REGION)
                .credentials(properties.accessKey(), properties.secretKey())
                .httpClient(httpClient)
                .build();
        this.signingClient = MinioClient.builder()
                .endpoint(properties.publicEndpoint())
                .region(REGION)
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
        this.bucket = properties.bucket();
    }

    @Override
    public PresignedUpload presignUpload(String objectKey, String contentType, long maxBytes, Duration expiration) {
        try {
            String stagingKey = STAGING_PREFIX + objectKey;
            PostPolicy policy = new PostPolicy(bucket, ZonedDateTime.now(clock).plus(expiration));
            policy.addEqualsCondition("key", stagingKey);
            policy.addEqualsCondition("Content-Type", contentType);
            policy.addContentLengthRangeCondition(1L, maxBytes);

            // La firma non include key e Content-Type: il client li invia come campi del form, e la policy li vincola
            Map<String, String> fields = new LinkedHashMap<>(signingClient.getPresignedPostFormData(policy));
            fields.put("key", stagingKey);
            fields.put("Content-Type", contentType);
            return new PresignedUpload(uploadUrl, Map.copyOf(fields));
        } catch (MinioException e) {
            log.error("Failed to generate presigned upload policy for key={}", objectKey, e);
            throw new StorageUnavailableException("Could not generate presigned upload policy", e);
        }
    }

    // Copia lato server (nessun byte passa dall'app). Il client può riscrivere lo staging fino alla scadenza della
    // policy, ma qualunque versione venga copiata è quella che il service poi verifica sull'oggetto finale
    @Override
    public Optional<StoredObject> promoteUpload(String objectKey) {
        String stagingKey = STAGING_PREFIX + objectKey;
        if (stat(stagingKey).isPresent()) {
            try {
                minioClient.copyObject(CopyObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .source(SourceObject.builder().bucket(bucket).object(stagingKey).build())
                        .build());
            } catch (MinioException e) {
                log.error("Failed to promote upload key={}", objectKey, e);
                throw new StorageUnavailableException("Could not promote uploaded object", e);
            }
            removeObject(stagingKey);
        }
        return stat(objectKey);
    }

    @Override
    public String generatePresignedDownloadUrl(String objectKey, String contentType, Duration expiration) {
        try {
            return signingClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Http.Method.GET)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry((int) expiration.toSeconds(), TimeUnit.SECONDS)
                            .extraQueryParams(Map.of(
                                    "response-content-type", contentType,
                                    "response-content-disposition", "attachment"
                            ))
                            .build()
            );
        } catch (MinioException e) {
            log.error("Failed to generate presigned download URL for key={}", objectKey, e);
            throw new StorageUnavailableException("Could not generate presigned download URL", e);
        }
    }

    @Override
    public Optional<StoredObject> stat(String objectKey) {
        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectKey).build()
            );
            return Optional.of(new StoredObject(stat.size(), stat.etag()));
        } catch (ErrorResponseException e) {
            String code = e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return Optional.empty();
            }
            log.error("Error verifying object presence on MinIO for key={}", objectKey, e);
            throw new StorageUnavailableException("Storage metadata lookup failed", e);
        } catch (MinioException e) {
            log.error("Unexpected error retrieving object metadata for key={}", objectKey, e);
            throw new StorageUnavailableException("Storage metadata lookup failed", e);
        }
    }

    @Override
    public byte[] readHead(String objectKey, int length) {
        try (GetObjectResponse in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).offset(0L).length((long) length).build())) {
            return in.readNBytes(length);
        } catch (MinioException | IOException e) {
            log.error("Failed to read object head for key={}", objectKey, e);
            throw new StorageUnavailableException("Could not read object from storage", e);
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        removeObject(objectKey);
        removeObject(STAGING_PREFIX + objectKey);
    }

    private void removeObject(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
            log.debug("Successfully removed physical object key={}", objectKey);
        } catch (MinioException e) {
            log.error("Failed to remove object from storage key={}", objectKey, e);
            throw new StorageUnavailableException("Could not delete object from storage", e);
        }
    }
}
