# ADR-0023: Modello delle frane: pendio indefinito con Monte Carlo e automa cellulare per la propagazione

- **Stato:** Proposto
- **Data:** 2026-10-17

## Contesto

Servono due risposte: dove un versante può cedere con una certa pioggia, e dove andrebbe il materiale. Non ci sono dati
geotecnici locali (coesione, attrito, spessore del suolo) per tutta la Calabria; ci sono il DEM e un inventario delle
frane (IFFI) utile per la validazione.

## Decisione

- **Suscettibilità:** modello del pendio indefinito con idrologia stazionaria, nella forma di SINMAP:
  `FS = (C + cos θ · (1 − w·r) · tan φ) / sin θ`, `w = min(1, R·a / (T·sin θ))`. I parametri incerti (`C`, `φ`, `T`)
  si campionano in intervalli (Monte Carlo) e il risultato è `P(FS < 1)` per cella, con la ricarica `R` da uno scenario
  di pioggia.
- **Propagazione:** automa cellulare semplificato ispirato a SCIDDICA-T (minimizzazione delle differenze su quota +
  spessore, aderenza, rilassamento), dall'innesco di una frana verificata o da un punto scelto dal gestore.
- Validazione con la curva ROC contro l'inventario IFFI; i risultati si espongono come stima indicativa.

## Conseguenze

- Modelli noti in letteratura, semplici da spiegare e da testare (casi calcolati a mano, piani sintetici,
  conservazione della massa).
- Senza geologia locale la mappa è approssimata: nessuna azione automatica sui sentieri, decide il `PARK_MANAGER`.
  L'app deve dire che non sostituisce il PAI né i bollettini della Protezione Civile.
- I parametri stanno in `simulation/config/soil-params.json` e si calibrano sull'AUC; classi del suolo da una mappa
  geologica e pioggia prevista da un servizio meteo restano fuori da M8.
