// Enum degli stati operativi di una squadra di soccorso, salvato come stringa in rescue_teams.status.
package it.unical.geosentinel.emergency.domain;

/**
 * Stato operativo di una {@link RescueTeam}. Solo le squadre AVAILABLE possono essere inviate su un'allerta.
 */
public enum TeamStatus {
    /** Pronta per essere inviata su un'allerta. */
    AVAILABLE,
    /** Impegnata su un'allerta SOS. */
    ON_MISSION,
    /** Non operativa. */
    OFF_DUTY
}
