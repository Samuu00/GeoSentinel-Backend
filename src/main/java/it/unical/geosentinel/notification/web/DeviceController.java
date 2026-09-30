package it.unical.geosentinel.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.identity.infrastructure.security.CurrentUser;
import it.unical.geosentinel.notification.application.DeviceService;
import it.unical.geosentinel.notification.web.dto.DeviceDtos.RegisterDeviceRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// L'app registra il token a ogni avvio e quando FCM lo rinnova (onNewToken); al logout chiama il DELETE
// prima di /auth/logout, finché ha ancora un access token valido
@RestController
@RequestMapping("/api/v1/devices")
@Tag(name = "Devices", description = "Push notification device registration (FCM)")
public class DeviceController {
    private final DeviceService deviceService;
    private final CurrentUser currentUser;

    public DeviceController(DeviceService deviceService, CurrentUser currentUser) {
        this.deviceService = deviceService;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "registerDevice", summary = "Register (or move to the current user) a device push token", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterDeviceRequest request) {
        deviceService.register(currentUser.getRequiredUserId(), request.token(), request.platform());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "unregisterDevice", summary = "Remove a device push token of the current user (idempotent)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> unregister(@PathVariable String token) {
        deviceService.unregister(currentUser.getRequiredUserId(), token);
        return ResponseEntity.noContent().build();
    }
}
