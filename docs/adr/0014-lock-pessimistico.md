# ADR-0014: Lock pessimistico per la capacità dei rifugi e gli SOS

- **Stato:** Accettato
- **Data:** 2026-09-24

## Contesto

Due prenotazioni simultanee che leggono la stessa occupazione possono superare la capacità, e due operatori possono inviare squadre sulla stessa allerta.

## Decisione

`SELECT ... FOR UPDATE` sulla riga del rifugio prima di contare l'occupazione notte per notte; sulle allerte, lock in ordine fisso allerta → squadra. `lock_timeout = 3s` impostato su ogni connessione.

## Conseguenze

- Le prenotazioni dello stesso rifugio sono serializzate: accettabile per i volumi previsti.
- Un timeout del lock diventa 409 `resource-busy` con `Retry-After`.
- Verificato da test di concorrenza ripetuti (`@RepeatedTest(5)`).
