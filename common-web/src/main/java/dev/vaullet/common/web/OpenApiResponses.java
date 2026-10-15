package dev.vaullet.common.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
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
 *   <li>an operation with no 2xx gets one, with the status its {@code @ResponseStatus} names (200
 *       without one), and the return type springdoc had put on the errors.
 * </ul>
 */
final class OpenApiResponses {

    static final String PROBLEM = "Problem";
    static final String FIELD_ERROR = "FieldError";
    static final String PROBLEM_JSON = "application/problem+json";

    private OpenApiResponses() {}

    /** Adds the error body's schemas to a document. Idempotent. */
    static void addProblemSchemas(OpenAPI api) {
        if (api.getComponents() == null) {
            api.setComponents(new Components());
        }
        api.getComponents().addSchemas(FIELD_ERROR, new Schema<>()
                .type("object")
                .description("One invalid field, named as the client sent it.")
                .addProperty("field", new Schema<>().type("string").example("external_ref"))
                .addProperty("message", new Schema<>().type("string").example("must not be blank"))
                .required(List.of("field")));
        api.getComponents().addSchemas(PROBLEM, new Schema<>()
                .type("object")
                .description("RFC 9457 problem details, with the platform's code and trace id (ADR-011, §7). "
                        + "Branch on code, not on title or detail.")
                .addProperty("type", new Schema<>().type("string").format("uri")
                        .example("https://docs.vaullet.dev/errors/resource-not-found"))
                .addProperty("title", new Schema<>().type("string").example("Resource not found"))
                .addProperty("status", new Schema<>().type("integer").format("int32").example(404))
                .addProperty("detail", new Schema<>().type("string")
                        .example("Account '7e57a11c-0000-4000-8000-000000000001' was not found"))
                .addProperty("instance", new Schema<>().type("string").format("uri-reference")
                        .example("/v1/accounts/7e57a11c-0000-4000-8000-000000000001"))
                .addProperty("code", new Schema<>().type("string").example("RESOURCE_NOT_FOUND")
                        .description("Stable, machine-readable. The contract a client branches on."))
                .addProperty("timestamp", new Schema<>().type("string").format("date-time"))
                .addProperty("trace_id", new Schema<>().type("string").example("6ad0e28f01e6291bc63fbf235c7f2ab0")
                        .description("Quote it when reporting a problem; it finds the request in the logs."))
                .addProperty("errors", new ArraySchema()
                        .items(new Schema<>().$ref("#/components/schemas/" + FIELD_ERROR))
                        .description("Only on VALIDATION_FAILED: each invalid field."))
                .required(List.of("type", "title", "status", "code")));
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
            responses.addApiResponse(String.valueOf(successStatus), success);
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
