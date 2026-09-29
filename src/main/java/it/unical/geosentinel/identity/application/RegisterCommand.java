package it.unical.geosentinel.identity.application;

import java.time.LocalDate;

// birthDate serve solo al controllo dell'età (M7.2.1): non arriva mai al database
public record RegisterCommand(
        String email,
        String rawPassword,
        String displayName,
        LocalDate birthDate
) {}
