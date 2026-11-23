// Enum dello stato di un file caricato dagli utenti. Il caricamento avviene direttamente dal client verso
// l'object storage, quindi il file nasce in attesa e diventa utilizzabile solo dopo la conferma e la
// verifica del contenuto da parte del server.
package it.unical.geosentinel.media.domain;

/**
 * Stato di un file media.
 */
public enum MediaStatus {
    /** Autorizzazione al caricamento generata, in attesa che il client invii il file e confermi. */
    PENDING_UPLOAD,
    /** Caricamento confermato e contenuto verificato: il file si può scaricare. */
    READY,
    /** Contenuto non conforme al momento della conferma: l'oggetto è stato cancellato, la riga resta per traccia. */
    QUARANTINED
}
