# ADR-0021: Limiti per utente sulle push SOS e sulle prenotazioni

- **Stato:** Accettato
- **Data:** 2026-10-06

## Contesto

L'audit di sicurezza del 05/10 (piste 3, 4, 5) ha trovato due abusi alla portata di un solo account HIKER:
- `POST /sos` è fuori dal rate limit (ADR-0005) e un SOS non si rifiuta mai (ADR-0012). Il ciclo raise → cancel → raise
  manda una push a tutti gli operatori a ogni giro.
- Le prenotazioni non hanno un tetto per utente né un orizzonte: un account occupa tutti i posti di un rifugio, e un
  check-in nel futuro lontano fa girare in `updateCapacity` un `generate_series` enorme sotto il lock del rifugio.

## Decisione

- **SOS:** l'allerta si crea sempre. La push agli operatori parte solo se l'escursionista ha creato meno di 3 allerte negli
  ultimi 15 minuti (conteggio sotto il suo advisory lock). Le altre si segnano come notificate (`notified_at`), così
  `SosNotificationRecovery` non le rilancia; restano nella lista degli operatori.
- **Prenotazioni:** check-in al massimo a 365 giorni da oggi e al massimo 5 prenotazioni confermate non ancora
  concluse per utente (422 in entrambi i casi). In più `statement_timeout = 30s` su ogni connessione.

## Conseguenze

- Un escursionista che annulla per errore tre SOS in 15 minuti non fa partire la push del quarto: gli operatori lo vedono
  comunque nella lista delle allerte attive. Rischio accettato in cambio della protezione da un flood di notifiche.
- Il tetto delle prenotazioni non è sotto un lock per utente (il lock è sul rifugio): richieste simultanee su rifugi
  diversi possono superarlo di poco.
- Nessuna query può tenere una connessione per più di 30 s: un job futuro più lento va spezzato in lotti.
