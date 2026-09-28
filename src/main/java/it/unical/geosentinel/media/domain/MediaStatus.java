package it.unical.geosentinel.media.domain;

public enum MediaStatus {
    PENDING_UPLOAD,   // Presigned URL generato, in attesa del push del client
    READY,            // Caricamento confermato e verificato
    QUARANTINED       // Dimensione o contenuto non conformi al confirm: oggetto cancellato, riga tenuta per traccia
}
