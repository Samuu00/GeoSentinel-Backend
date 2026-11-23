// Calcolo della chiave del client usata dai rate limit: l'indirizzo IP da cui arriva la richiesta, con gli
// indirizzi IPv6 raggruppati per rete /64. La usano il filtro del rate limit, il punto d'ingresso dei 401 e il
// limite dei tentativi di login per account.
package it.unical.geosentinel.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Chiave del client per i rate limit.
 * <p>
 * Si usa {@code getRemoteAddr()}: l'header {@code X-Forwarded-For} lo può scrivere chiunque e va considerato solo
 * dietro un proxy fidato. Un indirizzo IPv4 resta com'è. Per IPv6 si tengono solo
 * i primi 64 bit, perché una singola connessione domestica riceve di solito un'intera rete /64 e potrebbe
 * cambiare indirizzo a ogni richiesta per avere sempre un secchio nuovo.
 */
public final class ClientIp {
    /** Byte della parte di rete di un indirizzo IPv6 che si tengono nella chiave (64 bit). */
    private static final int IPV6_PREFIX_BYTES = 8;

    private ClientIp() {}

    /**
     * Chiave del client della richiesta.
     *
     * @param request richiesta HTTP
     * @return l'IPv4 così com'è, oppure la rete /64 di un IPv6 nella forma {@code esadecimale::/64}
     */
    public static String of(HttpServletRequest request) {
        return of(request.getRemoteAddr());
    }

    /**
     * Chiave del client della richiesta che il thread corrente sta servendo.
     *
     * @return la chiave, oppure vuoto se il codice non sta servendo una richiesta HTTP (per esempio un job)
     */
    public static Optional<String> current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return Optional.of(of(attributes.getRequest()));
        }
        return Optional.empty();
    }

    /**
     * Chiave del client a partire dall'indirizzo testuale.
     *
     * @param remoteAddr indirizzo restituito da {@code getRemoteAddr()}
     * @return l'IPv4 così com'è, oppure la rete /64 di un IPv6; un valore che non è un indirizzo resta invariato
     */
    static String of(String remoteAddr) {
        // Solo un letterale IPv6 contiene ":": così InetAddress non prova mai a risolvere un nome con il DNS
        if (remoteAddr == null || !remoteAddr.contains(":")) {
            return remoteAddr;
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            // Un IPv4 scritto in forma IPv6 (::ffff:a.b.c.d) torna come Inet4Address e resta un singolo indirizzo
            if (!(address instanceof Inet6Address)) {
                return address.getHostAddress();
            }
            byte[] network = new byte[IPV6_PREFIX_BYTES];
            System.arraycopy(address.getAddress(), 0, network, 0, IPV6_PREFIX_BYTES);
            return HexFormat.of().formatHex(network) + "::/64";
        } catch (UnknownHostException e) {
            return remoteAddr;
        }
    }
}
