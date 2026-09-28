package it.unical.geosentinel.media.domain;

// Entità a cui si può allegare un media. Enum e non stringa libera: il service verifica che il target esista
public enum MediaTargetType {
    TRAIL,
    HAZARD_REPORT
}
