// Vincolo di Bean Validation personalizzato che limita la lunghezza di una stringa in byte UTF-8 invece
// che in caratteri. Si usa sulle password: BCrypt accetta al massimo 72 byte e oltre lancia
// un'eccezione, che senza questo controllo arriverebbe al client come errore 500 invece che come 400.
package it.unical.geosentinel.shared.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * La stringa annotata non deve superare {@link #value()} byte una volta codificata in UTF-8.
 * {@code @Size} non basta perché conta i caratteri, e una lettera accentata occupa due byte.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxUtf8Bytes.Validator.class)
public @interface MaxUtf8Bytes {
    /** Numero massimo di byte ammessi. */
    int value();

    /** Messaggio di errore, con il segnaposto del limite. */
    String message() default "must not exceed {value} bytes in UTF-8";

    /** Gruppi di validazione (richiesto dalla specifica Bean Validation). */
    Class<?>[] groups() default {};

    /** Payload associati al vincolo (richiesto dalla specifica Bean Validation). */
    Class<? extends Payload>[] payload() default {};

    /**
     * Implementazione del controllo, registrata dall'annotazione {@code @Constraint}.
     */
    class Validator implements ConstraintValidator<MaxUtf8Bytes, String> {
        /** Limite in byte letto dall'annotazione. */
        private int max;

        /**
         * Legge il limite dall'annotazione applicata al campo.
         *
         * @param annotation istanza dell'annotazione
         */
        @Override
        public void initialize(MaxUtf8Bytes annotation) {
            this.max = annotation.value();
        }

        /**
         * Verifica la lunghezza in byte. Il valore {@code null} è considerato valido: l'obbligatorietà
         * del campo è compito di {@code @NotBlank}.
         *
         * @param value   stringa da controllare
         * @param context contesto della validazione (non usato)
         * @return {@code true} se la stringa è nulla o entro il limite
         */
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
        }
    }
}
