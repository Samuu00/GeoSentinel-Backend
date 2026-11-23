// Test unitario di ClientIp, che calcola la chiave del client per i rate limit: IPv4 invariato, IPv6 ridotto
// alla rete /64. Non servono né Spring né Redis.
package it.unical.geosentinel.identity.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica la chiave del client calcolata da {@link ClientIp}.
 */
class ClientIpTest {

    /** Un indirizzo IPv4 resta com'è. */
    @Test
    void ipv4IsKeptAsIs() {
        assertThat(ClientIp.of("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    /**
     * Due indirizzi IPv6 della stessa rete /64 danno la stessa chiave, mentre uno di un'altra rete ne dà una
     * diversa: cambiare indirizzo dentro la propria rete non regala un secchio nuovo.
     */
    @Test
    void ipv6IsGroupedByItsSlash64Network() {
        String first = ClientIp.of("2001:db8:1:2:aaaa:bbbb:cccc:1");
        String sameNetwork = ClientIp.of("2001:db8:1:2::ffff");
        String otherNetwork = ClientIp.of("2001:db8:1:3::1");

        assertThat(first).isEqualTo(sameNetwork).isEqualTo("20010db800010002::/64");
        assertThat(otherNetwork).isNotEqualTo(first);
    }

    /** Un IPv4 scritto in forma IPv6 torna un singolo indirizzo IPv4, senza essere raggruppato. */
    @Test
    void ipv4MappedAddressStaysASingleIpv4() {
        assertThat(ClientIp.of("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    /** Un valore che non è un indirizzo valido viene lasciato invariato invece di far fallire la richiesta. */
    @Test
    void invalidValueIsLeftUnchanged() {
        assertThat(ClientIp.of("non:un:indirizzo")).isEqualTo("non:un:indirizzo");
        assertThat(ClientIp.of((String) null)).isNull();
    }
}
