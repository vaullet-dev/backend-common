package io.vaullet.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.tracing.Tracer;
import io.vaullet.common.error.CommonErrorType;
import io.vaullet.common.error.ErrorType;
import io.vaullet.common.error.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The error contract, asserted at the wire.
 *
 * <p>This is the test that has to exist in the library rather than in each service. ADR-011, §7
 * makes {@code code} the contract every integrator branches on, so the keys and their values are as
 * much a published interface as any endpoint — and unlike an endpoint, nothing in a service's own
 * test suite fails when the shape quietly changes here.
 *
 * <p>Standalone {@code MockMvc} rather than {@code @WebMvcTest}: a library has no
 * {@code @SpringBootApplication} to slice, and this exercises the advice directly, which is the
 * thing under test.
 */
class ApiExceptionHandlerTest {

    private static final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiExceptionHandler(noTracer()))
            .build();

    @Test
    @DisplayName("a deliberate failure carries type, title, status, detail and the stable code")
    void applicationExceptionBecomesProblemJson() throws Exception {
        mockMvc.perform(get("/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ErrorType.DOCUMENTATION_BASE + "resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Widget 'w-1' was not found"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("a body validation failure lists the offending fields, sorted")
    void validationFailureListsFields() throws Exception {
        mockMvc.perform(post("/probe/widgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"size\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[1].field").value("size"));
    }

    @Test
    @DisplayName("an unexpected exception leaks neither its message nor its type")
    void unexpectedExceptionIsOpaque() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    @Test
    @DisplayName("lock contention is a 503 with Retry-After, not a 500")
    void lockContentionIsRetryable() throws Exception {
        mockMvc.perform(get("/probe/contended"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("RESOURCE_BUSY"));
    }

    @Test
    @DisplayName("a service can rename the lock-contention code without touching anything else")
    void lockContentionCodeIsOverridable() throws Exception {
        var overridden = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiExceptionHandler(noTracer()) {
                    @Override
                    protected ErrorType lockContentionErrorType() {
                        return CommonErrorType.RESOURCE_CONFLICT; // stands in for a service's own enum
                    }
                })
                .build();

        overridden.perform(get("/probe/contended")).andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    // ---------------------------------------------------------------------

    @RestController
    static class ProbeController {

        @GetMapping("/probe/missing")
        String missing() {
            throw new ResourceNotFoundException("Widget", "w-1");
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("connection to ledger_entries refused");
        }

        @GetMapping("/probe/contended")
        String contended() {
            throw new CannotAcquireLockException("could not obtain lock on row of relation \"accounts\"");
        }

        @PostMapping("/probe/widgets")
        String create(@Valid @RequestBody WidgetRequest request) {
            return request.name();
        }
    }

    record WidgetRequest(@NotBlank String name, @Min(1) int size) {}

    /**
     * Not every context has tracing — a {@code @WebMvcTest} slice typically does not — and the
     * handler must still answer. An error response missing its correlation id is far better than an
     * error handler that throws while handling an error.
     */
    private static ObjectProvider<Tracer> noTracer() {
        return new ObjectProvider<>() {
            @Override
            public Tracer getObject() {
                throw new NoSuchBeanDefinitionException(Tracer.class);
            }

            @Override
            public Tracer getIfAvailable() {
                return null;
            }
        };
    }
}
