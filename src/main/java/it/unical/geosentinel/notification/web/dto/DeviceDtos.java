// DTO dell'API REST dei dispositivi.
package it.unical.geosentinel.notification.web.dto;

import it.unical.geosentinel.notification.domain.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Contenitore dei record usati da {@code DeviceController}.
 */
public class DeviceDtos {
    /**
     * Corpo di POST /api/v1/devices. Il token è sempre quello di Firebase, anche su iOS, perché l'app usa l'SDK
     * Firebase e non il token APNs grezzo. La lunghezza massima coincide con quella della colonna token di
     * device_tokens.
     */
    public record RegisterDeviceRequest(
            @NotBlank(message = "Device token is required")
            @Size(max = 1024, message = "Device token must be at most 1024 characters")
            String token,

            @NotNull(message = "Platform is required")
            DevicePlatform platform
    ) {}
}
