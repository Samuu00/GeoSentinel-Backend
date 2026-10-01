package it.unical.geosentinel.shared.domain.exception;

// Account del personale (non HIKER): lo chiude l'organizzazione, non l'utente dall'app -> 409 account-managed
public class AccountManagedException extends DomainException {
    public AccountManagedException(String message) {
        super(message);
    }
}
