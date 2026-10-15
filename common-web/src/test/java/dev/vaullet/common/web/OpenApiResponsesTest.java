package dev.vaullet.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;

/**
 * springdoc puts the return type on every response that names no content, errors included, and
 * leaves out the success response of an operation that only declares its errors. These pin the fix.
 */
class OpenApiResponsesTest {

    private static final String ACCOUNT = "#/components/schemas/AccountResponse";
    private static final String PROBLEM = "#/components/schemas/" + OpenApiResponses.PROBLEM;

    /** What springdoc generates for a GET with only {@code @ApiResponse(404)} and {@code @ApiResponse(503)}. */
    private static Operation errorsOnly() {
        return new Operation().responses(new ApiResponses()
                .addApiResponse("404", withAccount("RESOURCE_NOT_FOUND"))
                .addApiResponse("503", withAccount("UPSTREAM_UNAVAILABLE")));
    }

    private static ApiResponse withAccount(String description) {
        return new ApiResponse().description(description)
                .content(new Content().addMediaType("*/*", new MediaType().schema(new Schema<>().$ref(ACCOUNT))));
    }

    private static String refOf(ApiResponse response, String mediaType) {
        return response.getContent().get(mediaType).getSchema().get$ref();
    }

    @Test
    @DisplayName("errors are problem+json with the Problem schema, not the success body")
    void errors_are_problems() {
        Operation operation = OpenApiResponses.describe(errorsOnly(), 200);

        assertThat(refOf(operation.getResponses().get("404"), "application/problem+json")).isEqualTo(PROBLEM);
        assertThat(operation.getResponses().get("404").getContent()).containsOnlyKeys("application/problem+json");
        assertThat(operation.getResponses().get("404").getDescription()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(refOf(operation.getResponses().get("503"), "application/problem+json")).isEqualTo(PROBLEM);
    }

    @Test
    @DisplayName("an operation that only declares errors gets its success response, with the return type")
    void adds_the_missing_success() {
        Operation operation = OpenApiResponses.describe(errorsOnly(), 200);

        assertThat(operation.getResponses()).containsKey("200");
        assertThat(refOf(operation.getResponses().get("200"), "application/json")).isEqualTo(ACCOUNT);
    }

    @Test
    @DisplayName("a declared success response is left alone")
    void keeps_a_declared_success() {
        Operation operation = errorsOnly();
        operation.getResponses().addApiResponse("201", new ApiResponse().description("Created")
                .content(new Content().addMediaType("application/json", new MediaType().schema(new Schema<>().$ref(ACCOUNT)))));

        OpenApiResponses.describe(operation, 200);

        assertThat(operation.getResponses()).containsOnlyKeys("201", "404", "503");
        assertThat(refOf(operation.getResponses().get("201"), "application/json")).isEqualTo(ACCOUNT);
    }

    @Test
    @DisplayName("a 204 handler gets a 204 without a body")
    void no_content() {
        Operation operation = new Operation().responses(new ApiResponses().addApiResponse("404", new ApiResponse()));

        OpenApiResponses.describe(operation, 204);

        assertThat(operation.getResponses().get("204").getContent()).isNull();
        assertThat(operation.getResponses().get("204").getDescription()).isEqualTo("No Content");
    }

    public static class Handlers {
        @ResponseStatus(HttpStatus.CREATED)
        public void created() {}

        public void plain() {}
    }

    @Test
    @DisplayName("the success status is the handler's @ResponseStatus, or 200")
    void success_status() throws NoSuchMethodException {
        var handlers = new Handlers();

        assertThat(OpenApiResponses.successStatus(new HandlerMethod(handlers, "created"))).isEqualTo(201);
        assertThat(OpenApiResponses.successStatus(new HandlerMethod(handlers, "plain"))).isEqualTo(200);
    }

    @Test
    @DisplayName("the Problem schema is the body ProblemDetails builds, in snake_case")
    void problem_schema() {
        var api = new OpenAPI();

        OpenApiResponses.addProblemSchemas(api);

        Schema<?> problem = api.getComponents().getSchemas().get(OpenApiResponses.PROBLEM);
        assertThat(problem.getProperties())
                .containsOnlyKeys("type", "title", "status", "detail", "instance", "code", "timestamp", "trace_id", "errors");
        assertThat(problem.getRequired()).contains("code");
        assertThat(api.getComponents().getSchemas()).containsKey(OpenApiResponses.FIELD_ERROR);
    }
}
