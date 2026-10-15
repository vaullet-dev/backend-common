package dev.vaullet.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * The document Swagger UI renders, read from a running application and held to the wire.
 *
 * <p>springdoc describes the models with a Jackson 2 mapper of its own, which knows nothing of
 * {@code spring.jackson.*}, so the document said {@code accountId} where the wire says
 * {@code account_id}. And it writes OpenAPI 3.1, where a schema built the 3.0 way loses its type and a
 * resolver built for 3.0 rewrites what it describes. These read the served document rather than the
 * pieces that make it, so they fail on anything that comes between the two.
 */
@SpringBootTest(
        classes = OpenApiDocumentTest.App.class,
        properties = "spring.config.import=classpath:vaullet/platform-defaults.yaml")
class OpenApiDocumentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("the document's property names are the wire's: snake_case, and an explicit @JsonProperty kept as written")
    void document_names_the_wire() throws Exception {
        JsonNode wire = JSON.readTree(mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"external_ref": "r-1", "identity": {"first_name": "Ana", "email_verified": true}}"""))
                .andReturn().getResponse().getContentAsString());
        // The wire really is snake_case, both ways: the body was read (external_ref came back) and written.
        assertThat(names(wire)).containsExactlyInAnyOrder("account_id", "external_ref", "identity", "legacyName");
        assertThat(wire.get("external_ref").asText()).isEqualTo("r-1");
        assertThat(names(wire.get("identity"))).containsExactlyInAnyOrder("first_name", "email_verified");

        JsonNode schemas = document().at("/components/schemas");

        assertThat(names(schemas.at("/AccountView/properties"))).containsExactlyInAnyOrderElementsOf(names(wire));
        assertThat(names(schemas.at("/Identity/properties"))).containsExactlyInAnyOrderElementsOf(names(wire.get("identity")));
        assertThat(names(schemas.at("/NewAccount/properties")))
                .containsExactlyInAnyOrder("external_ref", "identity", "daily_limit", "roles");
        // Required names are names too: a required list still in camelCase would make every generated
        // client, and Swagger UI's validation, insist on a field that does not exist.
        assertThat(schemas.at("/NewAccount/required").toString()).isEqualTo("[\"identity\"]");
    }

    @Test
    @DisplayName("described as OpenAPI 3.1: a $ref keeps its description, examples stay as written, items stay items")
    void described_as_openapi_31() throws Exception {
        JsonNode document = document();
        assertThat(document.get("openapi").asText()).startsWith("3.1");

        JsonNode properties = document.at("/components/schemas/NewAccount/properties");
        assertSoftly(softly -> {
            softly.assertThat(properties.at("/identity/$ref").asText()).isEqualTo("#/components/schemas/Identity");
            softly.assertThat(properties.at("/identity/description").asText()).isEqualTo("Who the account is for.");
            // Swagger UI's "Try it out" fills the body in from the examples, and 60.0 is not how money is written.
            softly.assertThat(properties.at("/daily_limit/example").asText()).isEqualTo("60.00");
            softly.assertThat(properties.at("/roles/example").toString()).isEqualTo("[\"SUPPORT\"]");
            softly.assertThat(names(properties.at("/roles/items"))).containsExactlyInAnyOrder("type", "enum");
        });
    }

    @Test
    @DisplayName("every schema, and every property of one, says what it is: a type or a $ref")
    void every_schema_says_what_it_is() throws Exception {
        JsonNode schemas = document().at("/components/schemas");
        List<String> untyped = new ArrayList<>();
        for (Map.Entry<String, JsonNode> schema : schemas.properties()) {
            if (!typed(schema.getValue())) {
                untyped.add(schema.getKey());
            }
            for (Map.Entry<String, JsonNode> property : schema.getValue().path("properties").properties()) {
                if (!typed(property.getValue())) {
                    untyped.add(schema.getKey() + "." + property.getKey());
                }
            }
        }

        // The error body is in here too (OpenApiResponses), so the hand-built schemas are checked as well.
        assertThat(names(schemas)).contains("NewAccount", OpenApiResponses.PROBLEM, OpenApiResponses.FIELD_ERROR);
        assertThat(untyped).isEmpty();
    }

    @Test
    @DisplayName("an operation that declares only its errors lists its success response first, and the errors as problems")
    void success_first() throws Exception {
        JsonNode responses = document().at("/paths/~1accounts~1{id}/get/responses");

        assertThat(names(responses)).first().isEqualTo("200");
        assertThat(names(responses)).contains("404");
        assertThat(responses.at("/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/AccountView");
        assertThat(responses.at("/404/content/application~1problem+json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/" + OpenApiResponses.PROBLEM);
    }

    @Test
    @DisplayName("a 400 Spring raises itself has every property the Problem schema requires")
    void spring_400_is_a_problem() throws Exception {
        JsonNode wire = JSON.readTree(mockMvc.perform(get("/accounts/not-a-uuid"))
                .andReturn().getResponse().getContentAsString());
        assertThat(wire.get("status").asInt()).isEqualTo(400);

        List<String> required = new ArrayList<>();
        document().at("/components/schemas/" + OpenApiResponses.PROBLEM + "/required").forEach(name -> required.add(name.asText()));
        assertThat(required).isNotEmpty().doesNotContain("code");
        assertThat(names(wire)).containsAll(required);
    }

    private JsonNode document() throws Exception {
        return JSON.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
    }

    private static boolean typed(JsonNode schema) {
        return schema.has("type") || schema.has("$ref") || schema.has("allOf") || schema.has("oneOf") || schema.has("anyOf");
    }

    private static List<String> names(JsonNode object) {
        return object.properties().stream().map(Map.Entry::getKey).toList();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Accounts.class)
    static class App {}

    @RestController
    static class Accounts {

        @PostMapping("/accounts")
        AccountView create(@RequestBody NewAccount body) {
            return new AccountView(
                    UUID.fromString("7e57a11c-0000-4000-8000-000000000001"), body.externalRef(), body.identity(), "kept");
        }

        /** Declares only its error, as the services do: the success response is OpenApiResponses' to add. */
        @GetMapping("/accounts/{id}")
        @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
        AccountView find(@PathVariable UUID id) {
            return new AccountView(id, "r-1", new Identity("Ana", true), "kept");
        }
    }

    /** Shaped like the services' bodies: a described nested object, money as a string, a set of roles. */
    record NewAccount(
            String externalRef,
            @Schema(description = "Who the account is for.") @NotNull Identity identity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "60.00") BigDecimal dailyLimit,
            @Schema(description = "At least one.", example = "[\"SUPPORT\"]") Set<Role> roles) {}

    record Identity(String firstName, boolean emailVerified) {}

    record AccountView(UUID accountId, String externalRef, Identity identity, @JsonProperty("legacyName") String renamed) {}

    enum Role { SUPPORT, FINANCE }
}
