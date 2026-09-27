package it.unical.geosentinel.emergency.domain;

public enum SosStatus {
    ACTIVE,         // Segnalazione aperta dall'escursionista
    DISPATCHED,     // Squadra di soccorso assegnata e in movimento
    RESOLVED,       // Soccorso completato con successo
    CANCELLED       // Falso allarme o annullata dall'utente/operatore
}
