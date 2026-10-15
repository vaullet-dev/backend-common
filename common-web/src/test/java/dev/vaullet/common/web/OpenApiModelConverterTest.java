package dev.vaullet.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * A service's own {@link ModelConverter} next to the platform's model resolver.
 *
 * <p>springdoc puts each converter bean at the front of swagger-core's chain, and the resolver ends the
 * chain: a converter behind it is never asked. Declared in the service, a converter is registered
 * before the auto-configured resolver, so without an order the resolver lands in front of it. This
 * holds that the service's converter still describes its type, and that the resolver still names
 * everything else as the wire does.
 */
@SpringBootTest(
        classes = OpenApiModelConverterTest.App.class,
        properties = "spring.config.import=classpath:vaullet/platform-defaults.yaml")
class OpenApiModelConverterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;

    @Test
    @DisplayName("a service's own ModelConverter still describes its type, and the names stay snake_case")
    void service_converter_runs() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        JsonNode schemas = JSON.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString())
                .at("/components/schemas");

        JsonNode properties = schemas.at("/Transfer/properties");
        assertThat(names(properties)).containsExactlyInAnyOrder("source_account", "amount_due");
        // The service's converter answered for AccountNumber: a string with its pattern, not an object.
        assertThat(properties.at("/source_account/type").asText()).isEqualTo("string");
        assertThat(properties.at("/source_account/pattern").asText()).isEqualTo(AccountNumbers.PATTERN);
        assertThat(names(schemas)).doesNotContain("AccountNumber");
    }

    private static List<String> names(JsonNode object) {
        return object.properties().stream().map(Map.Entry::getKey).toList();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Transfers.class)
    static class App {

        @Bean
        ModelConverter accountNumbers() {
            return new AccountNumbers();
        }
    }

    @RestController
    static class Transfers {

        @PostMapping("/transfers")
        Transfer create(@RequestBody Transfer body) {
            return body;
        }
    }

    record Transfer(AccountNumber sourceAccount, BigDecimal amountDue) {}

    /** A value type the service writes as a plain string. */
    record AccountNumber(String value) {}

    /**
     * How a service describes such a type: answers for its own and hands everything else on. It stays in
     * swagger-core's process-wide chain after this test, where it touches nothing but its own type.
     */
    static final class AccountNumbers implements ModelConverter {

        static final String PATTERN = "^[A-Z]{2}[0-9]{10}$";

        @Override
        public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
            if (type.getType() != null && Json.mapper().constructType(type.getType()).getRawClass() == AccountNumber.class) {
                return new StringSchema().pattern(PATTERN);
            }
            return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        }
    }
}
