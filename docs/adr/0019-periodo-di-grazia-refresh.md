# ADR-0019: Periodo di grazia sul refresh token appena ruotato

- **Stato:** Accettato
- **Data:** 2026-10-02

## Contesto

Ogni refresh token vale una volta sola e il riuso di un token già ruotato revoca l'intera famiglia (rilevamento del furto). L'app si usa in montagna, con la rete che cade spesso: se la connessione si interrompe dopo che il server ha ruotato il token ma prima che l'app legga la risposta, l'app tiene il token vecchio. Al refresh successivo il server lo vede riusato e revoca la sessione. L'utente si ritrova senza sessione, e quindi senza SOS dall'app, senza aver fatto nulla di sbagliato. Lato app il problema non si può risolvere: la coppia nuova esiste solo nella risposta persa.

## Decisione

Un refresh token revocato **da una rotazione** resta accettato per `geosentinel.jwt.refresh-reuse-grace` (60 s) dopo la rotazione, **solo se il suo successore non è mai stato usato**. In quel caso il server revoca il successore con un UPDATE atomico (`revokeIfActiveById`) ed emette una coppia nuova nella stessa famiglia, con la stessa scadenza assoluta.

- La colonna `refresh_tokens.replaced_by_id` (V10) collega un token al successore. Distingue un token revocato da una rotazione da uno revocato da logout, cambio password o furto: questi non hanno la grazia.
- La finestra si misura dalla prima rotazione e non si allunga con i tentativi successivi.
- Fuori dalla finestra, o se il successore è già stato usato o revocato, resta il comportamento precedente: revoca della famiglia e 401.

## Conseguenze

- Una risposta di refresh persa non chiude più la sessione, purché l'app riprovi entro 60 secondi.
- Un ladro che usa il token vecchio dentro la finestra ottiene una coppia valida, ma revoca il successore legittimo: al primo refresh dell'app legittima il riuso viene scoperto e la famiglia revocata, come prima. Il rischio accettato è al massimo una sessione rubata per la durata di un access token (15 minuti), lo stesso di un furto dell'access token.
- Due refresh in parallelo con lo stesso token restano un errore del client: ne vince uno, l'altro revoca la famiglia.
- Il contratto OpenAPI e la guida all'API descrivono l'eccezione; `AuthFlowIT` copre recupero, furto nella finestra, fuori finestra e logout.
