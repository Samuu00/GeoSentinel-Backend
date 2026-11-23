// Enum delle piattaforme dei dispositivi che ricevono le push, salvato come stringa in device_tokens.
package it.unical.geosentinel.notification.domain;

/**
 * Sistema operativo del dispositivo registrato. Su entrambe le piattaforme il token è quello di Firebase.
 */
public enum DevicePlatform {
    ANDROID,
    IOS
}
