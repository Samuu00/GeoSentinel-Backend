package it.unical.geosentinel.notification.web.dto;

import it.unical.geosentinel.notification.domain.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class DeviceDtos {
    // token FCM (anche su iOS: l'app usa l'SDK Firebase, non il token APNs grezzo). max come la colonna in V9
    public record RegisterDeviceRequest(
            @NotBlank(message = "Device token is required")
            @Size(max = 1024, message = "Device token must be at most 1024 characters")
            String token,

            @NotNull(message = "Platform is required")
            DevicePlatform platform
    ) {}
}
