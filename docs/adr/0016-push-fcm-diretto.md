# ADR-0016: Push con FCM ai token dei dispositivi, senza topic

- **Stato:** Accettato
- **Data:** 2026-09-30

## Contesto

Gli operatori di soccorso devono ricevere gli SOS anche con l'app chiusa, su Android e su iOS.

## Decisione

Firebase Cloud Messaging (anche per iOS, tramite APNs) dietro la porta `PushNotifier`. I destinatari per ruolo si calcolano a ogni invio (`UserQueryService.activeUserIdsWithRole`) e si invia ai loro token. Nel payload solo `type` e `alertId`. Spento di default (`LoggingPushNotifier`).

## Conseguenze

- Niente topic FCM: l'iscrizione andrebbe aggiornata a ogni cambio di ruolo, e un operatore disabilitato continuerebbe a ricevere gli allarmi.
- Una notifica non consegnata si riprova grazie a `notified_at`; può arrivare due volte e l'app deve tollerarlo.
- I token rifiutati da FCM vengono cancellati.
