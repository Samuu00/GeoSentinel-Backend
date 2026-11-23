// DTO dell'API REST per i sentieri: le richieste di creazione, modifica e cambio di stato, con i vincoli
// di Bean Validation, e le risposte con il dettaglio, il riepilogo per la mappa e il percorso tra due punti.
package it.unical.geosentinel.trail.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import it.unical.geosentinel.shared.web.geo.GeoJsonLineString;
import it.unical.geosentinel.trail.domain.Difficulty;
import it.unical.geosentinel.trail.domain.TrailStatus;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Contenitore dei record di richiesta e risposta dei sentieri.
 */
public class TrailDtos {
    /**
     * Corpo della richiesta di creazione di un sentiero.
     *
     * @param name        nome, da 3 a 150 caratteri
     * @param description descrizione facoltativa
     * @param difficulty  difficoltà
     * @param path        tracciato GeoJSON
     */
    public record CreateTrailRequest(
            @NotBlank(message = "Name is required")
            @Size(min = 3, max = 150, message = "Name must be between 3 and 150 characters")
            String name,
            String description,
            @NotNull(message = "Difficulty is required")
            Difficulty difficulty,
            @NotNull(message = "Path geometry is required")
            @Valid
            GeoJsonLineString path
    ) {}

    /**
     * Corpo della richiesta di modifica di un sentiero, con gli stessi vincoli della creazione.
     *
     * @param name        nome, da 3 a 150 caratteri
     * @param description descrizione facoltativa
     * @param difficulty  difficoltà
     * @param path        tracciato GeoJSON
     */
    public record UpdateTrailRequest(
            @NotBlank(message = "Name is required")
            @Size(min = 3, max = 150, message = "Name must be between 3 and 150 characters")
            String name,
            String description,
            @NotNull(message = "Difficulty is required")
            Difficulty difficulty,
            @NotNull(message = "Path geometry is required")
            @Valid
            GeoJsonLineString path
    ) {}

    /**
     * Corpo della richiesta di cambio di stato.
     *
     * @param status nuovo stato
     */
    public record UpdateTrailStatusRequest(@NotNull(message = "Status is required") TrailStatus status) {}
    /**
     * Dettaglio di un sentiero con il tracciato completo. lengthM non è mai nullo: è una colonna generata
     * dal database che il service rilegge con un flush prima di costruire la risposta.
     *
     * @param id          identificativo
     * @param name        nome
     * @param description descrizione, può essere {@code null}
     * @param difficulty  difficoltà
     * @param status      stato
     * @param path        tracciato completo
     * @param lengthM     lunghezza in metri
     */
    public record TrailDetailResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            String description,
            @Schema(requiredMode = REQUIRED) Difficulty difficulty,
            @Schema(requiredMode = REQUIRED) TrailStatus status,
            @Schema(requiredMode = REQUIRED) GeoJsonLineString path,
            @Schema(requiredMode = REQUIRED) Double lengthM
    ) {}

    /**
     * Percorso tra due punti calcolato sui sentieri non chiusi. Inizia e finisce nei punti della rete dei
     * sentieri a cui il motore ha agganciato partenza e arrivo.
     *
     * @param path     tracciato semplificato
     * @param lengthM  lunghezza in metri
     * @param trailIds sentieri del parco percorsi, in ordine
     */
    public record RouteResponse(
            @Schema(requiredMode = REQUIRED, description = "Path from the trail vertex nearest to the start to the one nearest to the destination")
            GeoJsonLineString path,
            @Schema(requiredMode = REQUIRED) Double lengthM,
            @Schema(requiredMode = REQUIRED, description = "Trails walked, in order") List<UUID> trailIds
    ) {}

    /**
     * Riepilogo di un sentiero per le liste e la mappa, con il tracciato semplificato.
     *
     * @param id         identificativo
     * @param name       nome
     * @param difficulty difficoltà
     * @param status     stato
     * @param lengthM    lunghezza in metri
     * @param path       tracciato semplificato (tolleranza di circa 5 metri)
     */
    public record TrailSummaryResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) Difficulty difficulty,
            @Schema(requiredMode = REQUIRED) TrailStatus status,
            @Schema(requiredMode = REQUIRED) Double lengthM,
            @Schema(requiredMode = REQUIRED, description = "Simplified path (about 5 m tolerance) for drawing the trail on a map; the full path is in the trail detail")
            GeoJsonLineString path
    ) {}
}
