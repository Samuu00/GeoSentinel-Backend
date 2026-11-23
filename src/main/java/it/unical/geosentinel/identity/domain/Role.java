/*
 * Ruoli degli utenti di GeoSentinel. Il ruolo viaggia nel JWT come claim e decide, insieme alle regole di
 * Spring Security, quali endpoint un utente può chiamare.
 */
package it.unical.geosentinel.identity.domain;

/**
 * Ruolo applicativo di un utente.
 */
public enum Role {
    /** Escursionista: il ruolo di chi si registra dall'app. */
    HIKER,
    /** Gestore del parco: verifica le segnalazioni, gestisce sentieri, POI e ticket di manutenzione. */
    PARK_MANAGER,
    /** Operatore del soccorso: prende in carico e chiude le allerte SOS. */
    RESCUE_OPERATOR,
    /** Amministratore: ha tutti i permessi, compresa la gestione degli altri utenti. */
    ADMIN
}
