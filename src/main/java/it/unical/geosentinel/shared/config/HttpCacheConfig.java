// Configurazione delle GET condizionali sulle rotte pubbliche del catalogo (sentieri e POI). Le liste e
// le geometrie GeoJSON possono pesare: l'app rimanda l'ultimo ETag ricevuto nell'header If-None-Match e,
// se nulla è cambiato, riceve un 304 senza body. L'ETag è l'hash del body, quindi la query sul database
// viene eseguita comunque: si risparmia solo il trasferimento in rete, che è ciò che conta su mobile.
// L'header Cache-Control "no-cache" di queste rotte lo imposta SecurityConfig (PUBLIC_CATALOG).
package it.unical.geosentinel.shared.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Registra il filtro {@link ShallowEtagHeaderFilter} sulle rotte del catalogo.
 */
@Configuration
public class HttpCacheConfig {
    /** Rotta del percorso tra due punti, esclusa dal filtro. */
    private static final String ROUTE_PATH = "/api/v1/trails/route";

    /**
     * Crea e registra il filtro che calcola l'ETag delle risposte del catalogo.
     *
     * @return la registrazione del filtro, limitata alle rotte sotto /api/v1/trails e /api/v1/pois
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> catalogEtagFilter() {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter() {
            /**
             * Esclude il percorso tra due punti: contiene la posizione dell'utente, quindi la risposta
             * non deve essere memorizzata (SecurityConfig la marca no-store) e non ha senso un ETag.
             *
             * @param request richiesta in arrivo
             * @return {@code true} se la richiesta è per la rotta del percorso
             */
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return ROUTE_PATH.equals(request.getRequestURI());
            }
        };
        // ETag debole (W/"..."): Tomcat non comprime le risposte che hanno un ETag forte, quindi senza
        // questa impostazione le GET del catalogo non verrebbero mai inviate in gzip. Il confronto con
        // If-None-Match è comunque debole, come prevede la RFC 9110.
        filter.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        // Nei pattern servlet l'asterisco finale copre anche i sotto-percorsi. Il filtro ha l'ordine di default, quindi
        // gira dopo la catena di Spring Security: le risposte 401 e 429 non passano dal calcolo
        // dell'hash. Il filtro agisce solo sulle GET con risposta 2xx.
        registration.addUrlPatterns("/api/v1/trails/*", "/api/v1/pois/*");
        registration.setName("catalogEtagFilter");
        return registration;
    }
}
