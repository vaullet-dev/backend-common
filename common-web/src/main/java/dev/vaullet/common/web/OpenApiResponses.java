package dev.vaullet.common.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;

/**
 * What the document says a response looks like, made to match what the service sends.
 *
 * <p>springdoc describes every {@code @ApiResponse} that names no content with the method's return
 * type, so a 404 on {@code GET /v1/accounts/{id}} claimed to return an account. And an operation that
 * declares only its errors gets no success response at all. Both are fixed here, once, rather than
 * with {@code content = @Content(...)} on every annotation in every service:
 *
 * <ul>
 *   <li>every 4xx and 5xx is {@code application/problem+json} with the {@link #PROBLEM} schema, the
 *       body {@link ProblemDetails} builds (ADR-011, §7);
 *   <li>an operation with no 2xx gets one, first, with the status its {@code @ResponseStatus} names
 *       (200 without one), and the return type springdoc had put on the errors.
 * </ul>
 */
final class OpenApiResponses {

    static final String PROBLEM = "Problem";
    static final String FIELD_ERROR = "FieldError";
    static final String PROBLEM_JSON = "application/problem+json";

    private OpenApiResponses() {}

    /**
     * Adds the error body's schemas to a document. Idempotent.
     *
     * <p>Built from the typed schema classes ({@link ObjectSchema}, {@link StringSchema}, ...), which set
     * both {@code type} (OpenAPI 3.0) and {@code types} (3.1). {@code new Schema<>().type("string")} sets
     * only the first, and the document springdoc writes is 3.1, so every field would come out untyped.
     */
    static void addProblemSchemas(OpenAPI api) {
        if (api.getComponents() == null) {
            api.setComponents(new Components());
        }
        api.getComponents().addSchemas(FIELD_ERROR, new ObjectSchema()
                .description("One invalid field, named as the client sent it.")
                .addProperty("field", new StringSchema().example("external_ref"))
                .addProperty("message", new StringSchema().example("must not be blank"))
                .required(List.of("field")));
        api.getComponents().addSchemas(PROBLEM, new ObjectSchema()
                .description("RFC 9457 problem details, with the platform's code and trace id (ADR-011, §7). "
                        + "Branch on code, not on title or detail.")
                .addProperty("type", new StringSchema().format("uri")
                        .example("https://docs.vaullet.dev/errors/resource-not-found"))
                .addProperty("title", new StringSchema().example("Resource not found"))
                .addProperty("status", new IntegerSchema().example(404))
                .addProperty("detail", new StringSchema()
                        .example("Account '7e57a11c-0000-4000-8000-000000000001' was not found"))
                .addProperty("instance", new StringSchema().format("uri-reference")
                        .example("/v1/accounts/7e57a11c-0000-4000-8000-000000000001"))
                .addProperty("code", new StringSchema().example("RESOURCE_NOT_FOUND")
                        .description("Stable, machine-readable. The contract a client branches on."))
                .addProperty("timestamp", new StringSchema().format("date-time"))
                .addProperty("trace_id", new StringSchema().example("6ad0e28f01e6291bc63fbf235c7f2ab0")
                        .description("Quote it when reporting a problem; it finds the request in the logs."))
                .addProperty("errors", new ArraySchema()
                        .items(new Schema<>().$ref("#/components/schemas/" + FIELD_ERROR))
                        .description("Only on VALIDATION_FAILED: each invalid field."))
                // Neither code nor type: a 400 Spring raises itself (a malformed body, a path variable
                // that is not a UUID) comes from ResponseEntityExceptionHandler with no code, and with no
                // type, which RFC 9457 reads as about:blank. A client validating against the document
                // would reject that response.
                .required(List.of("title", "status")));
    }

    /** Fixes one operation's responses. See the class Javadoc. */
    static Operation describe(Operation operation, int successStatus) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        @Nullable Schema<?> returned = responses.values().stream()
                .map(OpenApiResponses::schemaOf)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        if (responses.keySet().stream().noneMatch(OpenApiResponses::isSuccess)) {
            HttpStatus status = HttpStatus.valueOf(successStatus);
            ApiResponse success = new ApiResponse().description(status.getReasonPhrase());
            if (returned != null && status != HttpStatus.NO_CONTENT) {
                success.content(new Content().addMediaType(
                        org.springframework.http.MediaType.APPLICATION_JSON_VALUE, new MediaType().schema(returned)));
            }
            // First, ahead of the errors: Swagger UI lists responses in the document's order.
            Map<String, ApiResponse> declared = new LinkedHashMap<>(responses);
            responses.clear();
            responses.addApiResponse(String.valueOf(successStatus), success);
            responses.putAll(declared);
        }

        for (Map.Entry<String, ApiResponse> response : responses.entrySet()) {
            if (isError(response.getKey())) {
                response.getValue().content(new Content().addMediaType(
                        PROBLEM_JSON, new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
            }
        }
        return operation;
    }

    /** The status a handler answers with when it succeeds: its {@code @ResponseStatus}, or 200. */
    static int successStatus(HandlerMethod handler) {
        ResponseStatus annotation =
                AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), ResponseStatus.class);
        return annotation == null ? HttpStatus.OK.value() : annotation.code().value();
    }

    private static @Nullable Schema<?> schemaOf(ApiResponse response) {
        if (response.getContent() == null) {
            return null;
        }
        return response.getContent().values().stream()
                .map(MediaType::getSchema)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static boolean isSuccess(String code) {
        return code.startsWith("2");
    }

    private static boolean isError(String code) {
        return code.startsWith("4") || code.startsWith("5");
    }
}
