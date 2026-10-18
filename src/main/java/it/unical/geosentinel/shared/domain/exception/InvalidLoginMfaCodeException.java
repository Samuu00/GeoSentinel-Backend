package it.unical.geosentinel.shared.domain.exception;

// Stesso slug di InvalidMfaCodeException, ma 401: nel login il client non è ancora autenticato.
// Il handler sceglie la sottoclasse più specifica
public class InvalidLoginMfaCodeException extends InvalidMfaCodeException {}