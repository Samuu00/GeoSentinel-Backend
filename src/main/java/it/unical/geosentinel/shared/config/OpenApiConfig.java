package it.unical.geosentinel.shared.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import it.unical.geosentinel.shared.web.ProblemTypes;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "GeoSentinel API",
                version = "1.0",
                description = "Enterprise Outdoor Emergency and Trail Management System API",
                contact = @Contact(name = "GeoSentinel Team")
        )
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Inserisci l'Access Token JWT (Bearer <token>)"
)
public class OpenApiConfig {

    private static final String PROBLEM_SCHEMA = "ProblemDetail";
    private static final String PROBLEM_JSON = "application/problem+json";

    // Un solo punto per le risposte di errore (M5.3), invece di @ApiResponse ripetute su ogni endpoint: ogni
    // operazione riceve le risposte RFC 7807 che può davvero dare. Quelle già dichiarate (AuthController) restano
    @Bean
    public OpenApiCustomizer problemDetailResponses() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemDetailSchema());
            requirePageFields(openApi.getComponents().getSchemas());

            openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                boolean secured = operation.getSecurity() != null && !operation.getSecurity().isEmpty();
                addIfMissing(operation, "400", "Invalid request (validation-error)");
                if (secured) {
                    addIfMissing(operation, "401", "Missing, invalid, expired or revoked access token (unauthorized)");
                    addIfMissing(operation, "403", "Role not allowed (forbidden)");
                }
                if (path.contains("{")) {
                    addIfMissing(operation, "404", "Resource not found or not visible to the caller (not-found)");
                }
                // solo scritture autenticate: login e refresh non possono dare 409
                if (secured && method != PathItem.HttpMethod.GET) {
                    addIfMissing(operation, "409", "Conflict: concurrent modification, lock timeout or state conflict");
                }
                // header obbligatori (Idempotency-Key sulle prenotazioni, If-Match sui ticket): 428 se mancano
                if (hasHeader(operation, "Idempotency-Key", true) || hasHeader(operation, "If-Match", true)) {
                    addIfMissing(operation, "428", "Missing Idempotency-Key or If-Match header (precondition-required)");
                }
                addIfMissing(operation, "429", "Rate limit exceeded, see Retry-After (rate-limit-exceeded)");
                addIfMissing(operation, "500", "Unexpected error (internal-error)");
                documentIdempotentReplay(operation);
            }));
        };
    }

    private static boolean hasHeader(Operation operation, String name, boolean requiredOnly) {
        return operation.getParameters() != null && operation.getParameters().stream()
                .anyMatch(p -> "header".equals(p.getIn()) && name.equalsIgnoreCase(p.getName())
                        && (!requiredOnly || Boolean.TRUE.equals(p.getRequired())));
    }

    // D12: con Idempotency-Key un retry risponde 200 con la risorsa già creata (stesso body del 201)
    private static void documentIdempotentReplay(Operation operation) {
        ApiResponse created = operation.getResponses().get("201");
        if (hasHeader(operation, "Idempotency-Key", false) && created != null && !operation.getResponses().containsKey("200")) {
            operation.getResponses().addApiResponse("200", new ApiResponse()
                    .description("Retry with the same Idempotency-Key: the resource created by the first request")
                    .content(created.getContent()));
        }
    }

    private static void addIfMissing(Operation operation, String status, String description) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        if (operation.getResponses().containsKey(status)) {
            return;
        }
        operation.getResponses().addApiResponse(status, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_JSON, new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA)))));
    }

    // Lo schema del JSON che esce davvero: ProblemDetail di Spring con le properties portate al primo livello
    // (ProblemDetailJacksonMixin) da GlobalExceptionHandler e ProblemDetailWriter
    // M7.1: type/title/status ci sono sempre. Gli slug sono elencati nella descrizione e non come enum: un client
    // generato con un enum rigido fallirebbe la deserializzazione al primo slug nuovo
    private static Schema<?> problemDetailSchema() {
        return new ObjectSchema()
                .description("RFC 7807 problem details")
                .required(List.of("type", "title", "status"))
                .addProperty("type", new StringSchema().format("uri")
                        .description("https://geosentinel.dev/problems/<slug>, stable slugs: " + String.join(", ", problemSlugs())
                                + ". Clients must map the slug, never the detail text, and tolerate unknown slugs")
                        .example("https://geosentinel.dev/problems/validation-error"))
                .addProperty("title", new StringSchema().example("Validation error"))
                .addProperty("status", new IntegerSchema().example(400))
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema().format("uri").example("/api/v1/bookings"))
                .addProperty("timestamp", new StringSchema().format("date-time"))
                .addProperty("traceId", new StringSchema().example("4bf92f3577b34da6a3ce929d0e0e4736"))
                .addProperty("errors", new ArraySchema()
                        .description("Only for validation-error")
                        // title: nome della classe nel client generato (altrimenti ProblemDetailErrorsInner)
                        .items(new ObjectSchema()
                                .title("FieldError")
                                .required(List.of("field", "message"))
                                .addProperty("field", new StringSchema())
                                .addProperty("message", new StringSchema())));
    }

    // Una sola fonte: le costanti di ProblemTypes, in ordine alfabetico (la reflection non garantisce l'ordine)
    private static List<String> problemSlugs() {
        return Arrays.stream(ProblemTypes.class.getFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == URI.class)
                .map(f -> {
                    try {
                        String path = ((URI) f.get(null)).getPath();
                        return path.substring(path.lastIndexOf('/') + 1);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .sorted()
                .toList();
    }

    // Gli schemi della paginazione (PagedModel di Spring Data) non sono record nostri: content e page ci sono sempre
    private static void requirePageFields(Map<String, Schema> schemas) {
        if (schemas == null) {
            return;
        }
        schemas.forEach((name, schema) -> {
            if (name.startsWith("PagedModel") || name.equals("PageMetadata")) {
                schema.setRequired(schema.getProperties() == null ? null : List.copyOf(new TreeSet<>(schema.getProperties().keySet())));
            }
        });
    }
}
