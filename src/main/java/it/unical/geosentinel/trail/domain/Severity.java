package it.unical.geosentinel.trail.domain;

public enum Severity {
    LOW,        // Passaggio rallentato ma fattibile
    MEDIUM,     // Richiede attenzione e deviazioni parziali
    HIGH,       // Sentiero interrotto o pericolo imminente per l'incolumità
    CRITICAL    // Emergenza ambientale / Rischio vita immediato
}
