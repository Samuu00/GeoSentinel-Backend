// Classe base di tutti i test di integrazione (le classi *IT, eseguite dal plugin failsafe).
// Avvia l'applicazione Spring Boot completa su una porta casuale, collegata a PostGIS e Redis veri in
// container Testcontainers, e offre ai test una serie di utilità: creazione di utenti e token, chiamate
// HTTP reali, esecuzione concorrente di richieste e inserimento rapido di dati di prova via SQL.
package it.unical.geosentinel.support;

import it.unical.geosentinel.identity.application.AuthService;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Base comune dei test di integrazione.
 *
 * <p>I container di PostGIS e Redis sono singleton: partono una sola volta per JVM nel blocco statico e non
 * vengono mai fermati a mano (li chiude Ryuk, il processo di pulizia di Testcontainers, quando la JVM termina).
 * In questo modo la cache dei contesti di Spring resta valida tra una classe di test e l'altra e il contesto
 * non viene ricreato ogni volta.</p>
 *
 * <p>La classe non è {@code @Transactional} di proposito: nei test di concorrenza i thread di lavoro non
 * vedrebbero i dati di una transazione mai confermata, e il rollback finale nasconderebbe i lock. L'isolamento
 * tra un test e l'altro lo garantisce invece {@link #cleanDatabaseAndRedis()}, che svuota tabelle e Redis.</p>
 *
 * <p>Sono disponibili due modi di chiamare l'API: {@link #mockMvc}, che non passa dalla rete, e
 * {@link #call(HttpMethod, String, String, String, Map)}, che fa richieste HTTP vere verso Tomcat.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /** Password in chiaro di tutti gli utenti creati da {@link #createUser(Role)}, utile ai test di login. */
    protected static final String TEST_PASSWORD = "Password123!";

    /**
     * Container PostGIS, con la stessa immagine usata nel docker compose di sviluppo. La chiamata
     * {@code asCompatibleSubstituteFor("postgres")} serve perché altrimenti Testcontainers rifiuterebbe
     * un'immagine che non si chiama "postgres".
     */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    /** Container Redis, usato per cache, rate limit e chiavi di idempotenza. */
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

    // Avvio in parallelo dei due container. MinIO non viene avviato qui: MinioObjectStorage non fa chiamate di
    // rete all'avvio e bastano le chiavi finte di application-test.properties. I test che caricano davvero dei
    // file (MediaUploadIT) avviano un proprio container MinIO.
    static {
        Startables.deepStart(POSTGRES, REDIS).join();
    }

    /**
     * Collega l'applicazione ai container: indirizzi, credenziali e porte sono noti solo dopo l'avvio, quindi
     * vengono registrati come proprietà dinamiche al posto di quelle del file di configurazione.
     *
     * @param registry il registro delle proprietà fornito da Spring
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** Porta casuale su cui Spring ha avviato Tomcat. */
    @LocalServerPort
    private int port;

    /** Client MockMvc per le chiamate che non hanno bisogno di passare dalla rete. */
    @Autowired
    protected MockMvc mockMvc;

    /** Accesso diretto al database per preparare i dati e verificare lo stato dopo le chiamate. */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** Mapper JSON dell'applicazione, usato per leggere i corpi delle risposte. */
    @Autowired
    protected JsonMapper jsonMapper;

    /** Client Redis usato solo per svuotare Redis prima di ogni test. */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** Repository degli utenti, usato per creare utenti con qualsiasi ruolo. */
    @Autowired
    private UserRepository userRepository;

    /** Encoder delle password dell'applicazione (BCrypt). */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Componente che firma gli access token JWT, usato per ottenere un token senza passare dal login. */
    @Autowired
    private AuthService.JwtIssuer jwtIssuer;

    /**
     * Hash BCrypt di {@link #TEST_PASSWORD}, calcolato una sola volta per istanza della classe di test. Un hash
     * BCrypt costa circa 100 ms e alcuni test creano decine di utenti.
     */
    private String testPasswordHash;

    /**
     * Svuota tutte le tabelle applicative e Redis prima di ogni test. Si pulisce prima e non dopo, così anche
     * un test precedente interrotto a metà non lascia dati sporchi. Il FLUSHALL di Redis azzera anche i
     * contatori del rate limit (per IP e per account sul login).
     */
    @BeforeEach
    void cleanDatabaseAndRedis() {
        jdbcTemplate.execute("""
                TRUNCATE device_tokens, media_files, sos_alerts, rescue_teams, maintenance_tickets, hazard_reports,
                         bookings, refuges, pois, trails, refresh_tokens, users CASCADE
                """);
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            flushAll(connection);
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // Utenti e token
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Crea un utente con il ruolo indicato e restituisce un access token valido per lui.
     *
     * @param role il ruolo dell'utente da creare
     * @return l'access token JWT dell'utente appena creato
     */
    protected String obtainToken(Role role) {
        return tokenFor(createUser(role));
    }

    /**
     * Emette un access token per un utente già esistente.
     *
     * @param user l'utente per cui firmare il token
     * @return il valore del token JWT
     */
    protected String tokenFor(User user) {
        return jwtIssuer.issueAccessToken(user).tokenValue();
    }

    /**
     * Crea e salva un utente con il ruolo indicato, email univoca e password {@link #TEST_PASSWORD}, così i test
     * possono anche fare login. Passa dal repository e non dall'API di registrazione, perché quest'ultima crea
     * solo utenti con ruolo {@code HIKER}.
     *
     * @param role il ruolo da assegnare
     * @return l'utente salvato nel database
     */
    protected User createUser(Role role) {
        if (testPasswordHash == null) {
            testPasswordHash = passwordEncoder.encode(TEST_PASSWORD);
        }
        String email = role.name().toLowerCase() + "-" + UUID.randomUUID() + "@test.dev";
        return userRepository.saveAndFlush(User.create(email, testPasswordHash, "Test " + role.name(), role));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Chiamate HTTP vere sulla porta del server. Servono ai test di concorrenza e a quelli sui filtri,
    // perché MockMvc non passa da Tomcat.
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Risultato di una chiamata HTTP reale.
     *
     * @param status  codice di stato HTTP
     * @param headers header della risposta
     * @param body    corpo della risposta letto come testo UTF-8
     */
    protected record HttpResult(int status, HttpHeaders headers, String body) {
    }

    /**
     * Restituisce l'indirizzo base del server di test, per i test che usano un client HTTP proprio (ad esempio
     * per leggere risposte compresse con gzip senza la decompressione automatica).
     *
     * @return l'URL {@code http://localhost:<porta>}
     */
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Esegue una chiamata HTTP senza header aggiuntivi.
     *
     * @see #call(HttpMethod, String, String, String, Map)
     */
    protected HttpResult call(HttpMethod method, String path, String token, String jsonBody) {
        return call(method, path, token, jsonBody, Map.of());
    }

    /**
     * Esegue una chiamata HTTP vera verso l'applicazione e restituisce stato, header e corpo, senza lanciare
     * eccezioni sulle risposte 4xx e 5xx.
     *
     * @param method   il metodo HTTP
     * @param path     il percorso della risorsa, ad esempio {@code /api/v1/bookings}
     * @param token    l'access token da mandare come bearer, oppure {@code null} per una chiamata anonima
     * @param jsonBody il corpo JSON della richiesta, oppure {@code null} se non c'è
     * @param headers  header aggiuntivi, ad esempio {@code Idempotency-Key}
     * @return il risultato della chiamata
     */
    protected HttpResult call(HttpMethod method, String path, String token, String jsonBody, Map<String, String> headers) {
        // Si sceglie esplicitamente il client HTTP del JDK. Con httpclient5 nel classpath (arriva come
        // dipendenza di firebase-admin) RestClient.create() userebbe Apache HttpClient, che ripete da solo le
        // richieste dopo un 429 o un 503 con Retry-After e nasconderebbe ai test proprio quelle risposte.
        RestClient.RequestBodySpec request = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port)
                .build()
                .method(method)
                .uri(path)
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                    headers.forEach(h::set);
                });
        if (jsonBody != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        }
        // exchange non lancia eccezioni sui 4xx e 5xx: è il test a leggere stato e corpo.
        return request.exchange((req, res) -> new HttpResult(
                res.getStatusCode().value(),
                res.getHeaders(),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    /**
     * Esaurisce il bucket del rate limit per IP condiviso da login e registrazione, mandando registrazioni con
     * corpo vuoto (rifiutate subito con 400, senza calcolare BCrypt).
     *
     * <p>Il ciclo si ferma quando il bucket risulta vuoto e non dopo un numero fisso di richieste: il bucket si
     * ricarica nel tempo (30 richieste al minuto, cioè un gettone ogni 2 secondi) e su una CI lenta un conteggio
     * fisso potrebbe lasciare un gettone disponibile.</p>
     *
     * @throws IllegalStateException se dopo 1000 richieste il limite non è ancora stato raggiunto
     */
    protected void exhaustAuthRateLimit() {
        for (int i = 0; i < 1_000; i++) {
            HttpResult result = call(HttpMethod.POST, "/api/v1/auth/register", null, "{}");
            if (result.status() == 429 || "0".equals(result.headers().getFirst("X-RateLimit-Remaining"))) {
                return;
            }
        }
        throw new IllegalStateException("auth rate limit never reached");
    }

    /**
     * Legge un corpo di risposta come albero JSON.
     *
     * @param body il testo JSON
     * @return il nodo radice
     */
    protected JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }

    /**
     * Dice se la risposta ha content type {@code application/problem+json}. Si confrontano solo tipo e
     * sottotipo: le risposte dei filtri (401, 403, 429) aggiungono {@code ;charset=UTF-8}, che non cambia il
     * contratto.
     *
     * @param result la risposta da controllare
     * @return {@code true} se è un ProblemDetail
     */
    protected static boolean isProblemJson(HttpResult result) {
        MediaType contentType = result.headers().getContentType();
        return contentType != null && contentType.equalsTypeAndSubtype(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Concorrenza
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Esegue {@code threads} task in parallelo facendoli partire nello stesso istante. Ogni thread segnala di
     * essere pronto sul primo latch e poi aspetta il secondo, che viene aperto solo quando tutti sono pronti:
     * così le richieste arrivano al server il più possibile insieme.
     *
     * @param threads numero di task, ognuno su un thread proprio
     * @param task    funzione che riceve l'indice del task e restituisce il suo risultato
     * @return i risultati nell'ordine degli indici dei task
     * @throws Exception se un task fallisce o non termina entro 60 secondi
     */
    protected <T> List<T> runConcurrently(int threads, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(index);
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Dati di prova inseriti via SQL: più veloci delle API e indipendenti dai permessi dei ruoli.
    // Le coordinate sono sempre nell'ordine (longitudine latitudine).
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Inserisce un sentiero aperto di difficoltà {@code MODERATE}.
     *
     * @param name          il nome del sentiero
     * @param lineStringWkt il tracciato in formato WKT, ad esempio {@code LINESTRING(16.48 39.30, 16.49 39.30)}
     * @return l'id del sentiero creato
     */
    protected UUID insertTrail(String name, String lineStringWkt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO trails (id, name, difficulty, status, path)
                VALUES (gen_random_uuid(), ?, 'MODERATE', 'OPEN', ST_GeomFromText(?, 4326))
                RETURNING id
                """, UUID.class, name, lineStringWkt);
    }

    /**
     * Inserisce un rifugio in una posizione fissa in Calabria.
     *
     * @param capacity  i posti letto
     * @param managerId l'id dell'utente che gestisce il rifugio
     * @return l'id del rifugio creato
     */
    protected UUID insertRefuge(int capacity, UUID managerId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO refuges (id, name, location, capacity, manager_id)
                VALUES (gen_random_uuid(), 'Rifugio Test', ST_SetSRID(ST_MakePoint(16.45, 39.28), 4326), ?, ?)
                RETURNING id
                """, UUID.class, capacity, managerId);
    }

    /**
     * Inserisce una squadra di soccorso disponibile, con una base in posizione fissa.
     *
     * @param name il nome della squadra
     * @return l'id della squadra creata
     */
    protected UUID insertTeam(String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO rescue_teams (id, name, call_sign, contact_number, status, base_location)
                VALUES (gen_random_uuid(), ?, 'CALL', '+390000000', 'AVAILABLE', ST_SetSRID(ST_MakePoint(16.45, 39.33), 4326))
                RETURNING id
                """, UUID.class, name);
    }

    /**
     * Svuota tutti i database di Redis.
     *
     * @param connection la connessione a Redis fornita dal template
     */
    private static void flushAll(RedisConnection connection) {
        connection.serverCommands().flushAll();
    }
}
