package dev.vaullet.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Catalogue integrity.
 *
 * <p>These assertions look trivial and they are: the point is that a published error catalogue is a
 * contract, and the ways it gets broken are all mechanical. A slug copy-pasted from the entry above
 * makes two codes resolve to the same documentation page, and nobody notices until an integrator
 * follows the link.
 */
class CommonErrorTypeTest {

    @Test
    @DisplayName("code() is the enum constant name, which is what ADR-011 publishes")
    void codeIsTheConstantName() {
        assertThat(CommonErrorType.RESOURCE_NOT_FOUND.code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("every type URI is distinct and sits under the documented base")
    void typeUrisAreDistinctAndWellFormed() {
        var uris = Arrays.stream(CommonErrorType.values())
                .map(errorType -> errorType.type().toString())
                .toList();

        assertThat(uris).allMatch(uri -> uri.startsWith(ErrorType.DOCUMENTATION_BASE)).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the slug is the kebab-case form of the code")
    void slugMatchesCode() {
        for (var errorType : CommonErrorType.values()) {
            var expected = errorType.code().toLowerCase().replace('_', '-');
            assertThat(errorType.type().toString()).endsWith("/" + expected);
        }
    }

    @Test
    @DisplayName("nothing in the common catalogue is a 2xx")
    void everyEntryIsAFailure() {
        assertThat(CommonErrorType.values()).allSatisfy(errorType -> assertThat(
                        errorType.status().isError())
                .as("%s", errorType)
                .isTrue());
    }
}
