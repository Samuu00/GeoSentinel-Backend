// Punto di ingresso del backend GeoSentinel, l'applicazione Spring Boot che serve l'app mobile per chi
// va in montagna: sentieri, segnalazioni di pericolo, rifugi, SOS e strumenti per gestori e soccorritori.
// L'annotazione @SpringBootApplication attiva l'autoconfigurazione e la scansione dei componenti a partire
// da questo package.
package it.unical.geosentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Classe principale dell'applicazione.
 */
@SpringBootApplication
public class GeoSentinelApplication {

    /**
     * Avvia il contesto Spring e il server web incorporato.
     *
     * @param args argomenti da riga di comando, passati a Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(GeoSentinelApplication.class, args);
    }

}
