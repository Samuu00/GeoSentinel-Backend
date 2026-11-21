package it.unical.geosentinel.shared.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

// GET condizionali sulle rotte pubbliche dei sentieri e dei POI: liste e GeoJSON possono pesare,
// l'app rimanda l'ETag in If-None-Match e riceve 304 senza body se nulla è cambiato.
// L'ETag è l'hash del body: la query gira comunque, si risparmia solo la rete (quella che conta su mobile).
// Il Cache-Control "no-cache" di queste rotte lo scrive SecurityConfig (PUBLIC_CATALOG)
@Configuration
public class HttpCacheConfig {
    private static final String ROUTE_PATH = "/api/v1/trails/route";

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> catalogEtagFilter() {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter() {
            // il percorso A→B (ADR-0027) contiene la posizione dell'utente: no-store e niente ETag (SecurityConfig)
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return ROUTE_PATH.equals(request.getRequestURI());
            }
        };
        // ETag debole (W/"..."): Tomcat non comprime le risposte con un ETag forte, e senza questa riga
        // le GET del catalogo non sarebbero mai gzip. If-None-Match usa comunque il confronto debole (RFC 9110)
        filter.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        // pattern servlet: "/*" copre anche i sotto-percorsi. Registrato dopo la catena di Spring Security
        // (ordine di default): 401 e 429 non passano dal calcolo dell'hash. Il filtro lavora solo su GET 2xx
        registration.addUrlPatterns("/api/v1/trails/*", "/api/v1/pois/*");
        registration.setName("catalogEtagFilter");
        return registration;
    }
}
