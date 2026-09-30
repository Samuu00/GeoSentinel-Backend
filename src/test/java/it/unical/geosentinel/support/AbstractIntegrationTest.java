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

// Base degli integration test (*IT, failsafe). Container singleton: avviati una volta per JVM nel blocco static
// e mai fermati a mano (li chiude Ryuk a fine JVM), così il context cache di Spring resta valido tra le classi.
// Niente @Transactional qui: nei test di concorrenza i thread worker non vedrebbero i dati e il rollback
// nasconderebbe i lock. L'isolamento lo dà la pulizia in cleanDatabaseAndRedis().
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final String TEST_PASSWORD = "Password123!";

    // Stessa immagine del compose. asCompatibleSubstituteFor: senza, Testcontainers rifiuta un'immagine non "postgres"
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

    // MinIO non serve qui: MinioObjectStorage non fa chiamate di rete all'avvio (bastano le chiavi finte di
    // application-test.properties). Il futuro MediaUploadIT aggiungerà il suo container.
    static {
        Startables.deepStart(POSTGRES, REDIS).join();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuthService.JwtIssuer jwtIssuer;

    // BCrypt costa ~100 ms: un solo hash per classe, anche quando un test crea 50 utenti
    private String testPasswordHash;

    // Prima di ogni test (non dopo): anche se il test precedente è morto a metà, si parte da uno stato pulito.
    // FLUSHALL azzera anche i bucket del rate limit (geosentinel.rate-limit.*) e il limite per account sul login
    @BeforeEach
    void cleanDatabaseAndRedis() {
        jdbcTemplate.execute("""
                TRUNCATE media_files, sos_alerts, rescue_teams, maintenance_tickets, hazard_reports,
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

    // Crea un utente con quel ruolo (password TEST_PASSWORD, così i test possono anche fare login) e restituisce
    // un access token valido. Passa da UserRepository perché la registrazione crea solo HIKER
    protected String obtainToken(Role role) {
        return tokenFor(createUser(role));
    }

    protected String tokenFor(User user) {
        return jwtIssuer.issueAccessToken(user).tokenValue();
    }

    protected User createUser(Role role) {
        if (testPasswordHash == null) {
            testPasswordHash = passwordEncoder.encode(TEST_PASSWORD);
        }
        String email = role.name().toLowerCase() + "-" + UUID.randomUUID() + "@test.dev";
        return userRepository.saveAndFlush(User.create(email, testPasswordHash, "Test " + role.name(), role));
    }

    // ------------------------------------------------------------------------------------------------------------
    // HTTP vero sulla porta del server (serve ai test di concorrenza: MockMvc non passa da Tomcat)
    // ------------------------------------------------------------------------------------------------------------

    protected record HttpResult(int status, HttpHeaders headers, String body) {
    }

    // per i test che non passano da RestClient (es. risposte gzip lette senza decompressione automatica)
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected HttpResult call(HttpMethod method, String path, String token, String jsonBody) {
        return call(method, path, token, jsonBody, Map.of());
    }

    protected HttpResult call(HttpMethod method, String path, String token, String jsonBody, Map<String, String> headers) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port)
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
        // exchange: nessuna eccezione sui 4xx/5xx, il test legge status e body
        return request.exchange((req, res) -> new HttpResult(
                res.getStatusCode().value(),
                res.getHeaders(),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    // Esaurisce il bucket per IP di login/register con register non validi (400 immediati, senza BCrypt).
    // Si ferma quando il bucket è vuoto, non dopo N richieste: durante il ciclo il bucket si ricarica
    // (30/min = un gettone ogni 2 s) e su una CI lenta un conteggio fisso lascerebbe un gettone
    protected void exhaustAuthRateLimit() {
        for (int i = 0; i < 1_000; i++) {
            HttpResult result = call(HttpMethod.POST, "/api/v1/auth/register", null, "{}");
            if (result.status() == 429 || "0".equals(result.headers().getFirst("X-RateLimit-Remaining"))) {
                return;
            }
        }
        throw new IllegalStateException("auth rate limit never reached");
    }

    protected JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }

    // Solo tipo e sottotipo: dai filtri (401/403/429) arriva anche ";charset=UTF-8", che non cambia il contratto
    protected static boolean isProblemJson(HttpResult result) {
        MediaType contentType = result.headers().getContentType();
        return contentType != null && contentType.equalsTypeAndSubtype(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Concorrenza: N task che partono insieme (CountDownLatch), risultati nell'ordine dei task
    // ------------------------------------------------------------------------------------------------------------

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
    // Fixture via SQL: più veloci delle API e indipendenti dai ruoli. Coordinate sempre (lon lat)
    // ------------------------------------------------------------------------------------------------------------

    protected UUID insertTrail(String name, String lineStringWkt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO trails (id, name, difficulty, status, path)
                VALUES (gen_random_uuid(), ?, 'MODERATE', 'OPEN', ST_GeomFromText(?, 4326))
                RETURNING id
                """, UUID.class, name, lineStringWkt);
    }

    protected UUID insertRefuge(int capacity, UUID managerId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO refuges (id, name, location, capacity, manager_id)
                VALUES (gen_random_uuid(), 'Rifugio Test', ST_SetSRID(ST_MakePoint(16.45, 39.28), 4326), ?, ?)
                RETURNING id
                """, UUID.class, capacity, managerId);
    }

    protected UUID insertTeam(String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO rescue_teams (id, name, call_sign, contact_number, status, base_location)
                VALUES (gen_random_uuid(), ?, 'CALL', '+390000000', 'AVAILABLE', ST_SetSRID(ST_MakePoint(16.45, 39.33), 4326))
                RETURNING id
                """, UUID.class, name);
    }

    private static void flushAll(RedisConnection connection) {
        connection.serverCommands().flushAll();
    }
}
