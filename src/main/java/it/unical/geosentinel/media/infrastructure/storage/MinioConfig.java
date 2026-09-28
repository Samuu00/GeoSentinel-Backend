package it.unical.geosentinel.media.infrastructure.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// Il bucket lo crea minio-init nel compose (privato): niente chiamate di rete durante l'avvio del contesto
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {}
