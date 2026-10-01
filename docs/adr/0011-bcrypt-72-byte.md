# ADR-0011: Password con BCrypt e limite di 72 byte UTF-8

- **Stato:** Accettato
- **Data:** 2026-09-22

## Contesto

BCrypt usa solo i primi 72 byte e Spring Security, dalla correzione della CVE-2025-22228, lancia un'eccezione oltre quella soglia: senza un limite in validazione una password lunga diventa un 500.

## Decisione

BCrypt con l'encoder delegante (`{bcrypt}`). Password da 12 caratteri a 72 byte UTF-8, validate da `@MaxUtf8Bytes`: il limite è in byte, non in caratteri.

## Conseguenze

- Login a tempo costante: con un'email inesistente si verifica un hash fittizio, così i tempi non rivelano gli account.
- Alternativa scartata: Argon2, che richiederebbe Bouncy Castle.
