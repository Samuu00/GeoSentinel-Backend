package it.unical.geosentinel.trail.domain;

public enum HazardStatus {
    SUBMITTED,   // Inviata dall'escursionista, in attesa di verifica
    VERIFIED,    // Verificata dal personale del parco e confermata
    REJECTED,    // Respinta (falso allarme o duplicato non rilevante)
    RESOLVED     // Pericolo rimosso o sentiero ripristinato
}
