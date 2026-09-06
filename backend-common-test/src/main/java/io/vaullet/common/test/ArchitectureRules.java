package io.vaullet.common.test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * The {@code @vaullet-io} layering rules, enforced by each service's build instead of by code
 * review.
 *
 * <p>Architecture documented only in a README decays: the first pull request that injects a
 * repository into a controller because it was quicker will be approved by someone in a hurry, and
 * after three of those the layers exist in name only. These rules fail that pull request instead.
 *
 * <p>They live in the shared library rather than being copied because a rule that is copied is a
 * rule that is edited locally. A service that finds one of these genuinely wrong should say so and
 * change it here, for everyone — that conversation is the value, and a local edit skips it.
 *
 * <p>The rules are deliberately few. A rule nobody can justify gets suppressed rather than obeyed,
 * so each one here is a boundary that would actually cost something to lose.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "io.vaullet.ledger", importOptions = ImportOption.DoNotIncludeTests.class)
 * class LayeringTest {
 *
 *     private static final String BASE = "io.vaullet.ledger";
 *
 *     @ArchTest
 *     static final ArchRule layers = ArchitectureRules.layersAreRespected(BASE);
 *
 *     @ArchTest
 *     static final ArchRule http = ArchitectureRules.serviceLayerKnowsNothingAboutHttp(BASE);
 * }
 * }</pre>
 */
public final class ArchitectureRules {

    private ArchitectureRules() {}

    /**
     * Package-by-feature, layered inside: {@code <base>..api..} → {@code ..service..} →
     * {@code ..dao..}, and never the other way.
     *
     * <p>{@code consideringOnlyDependenciesInLayers()} means a class in none of the three layers —
     * {@code config}, {@code common} — is simply not part of this rule, rather than being an implicit
     * violation.
     */
    public static ArchRule layersAreRespected(String basePackage) {
        return Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("API")
                .definedBy(basePackage + "..api..")
                .layer("Service")
                .definedBy(basePackage + "..service..")
                .layer("DAO")
                .definedBy(basePackage + "..dao..")
                .whereLayer("API")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Service")
                .mayOnlyBeAccessedByLayers("API")
                .whereLayer("DAO")
                .mayOnlyBeAccessedByLayers("Service");
    }

    /**
     * The single most common shortcut: a controller that "just needs one query".
     *
     * <p>It couples the HTTP contract to the schema and, worse, puts the transaction boundary in the
     * wrong place — a controller holding a repository is free to read state outside a transaction
     * and act on it, which is exactly the mistake ADR-001 made.
     */
    public static ArchRule controllersDoNotReachIntoRepositories() {
        return noClasses()
                .that()
                .haveSimpleNameEndingWith("Controller")
                .should()
                .dependOnClassesThat()
                .areAnnotatedWith(Repository.class)
                .because("controllers must go through the service layer, which owns transactions and rules");
    }

    /**
     * Keeps the web stack out of the rules.
     *
     * <p>Not a purity argument: ADR-004's revised flow settles and releases from a Kafka listener, so
     * a service method that reached for a servlet type would not be callable from the path that
     * actually settles money.
     */
    public static ArchRule serviceLayerKnowsNothingAboutHttp(String basePackage) {
        return noClasses()
                .that()
                .resideInAPackage(basePackage + "..service..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("jakarta.servlet..", "org.springframework.web..", "org.springframework.http..")
                .because("the same rules are driven by a message listener, which has no request to bind");
    }

    /**
     * Persistence technology stays behind the DAO layer.
     *
     * <p>Parameterised because the platform runs two access technologies: services on Spring Data JPA
     * pass {@code "jakarta.persistence.."}, and a service that rejected an ORM because the SQL
     * <em>is</em> the design (ADR-004) passes {@code "org.springframework.jdbc.."}. The boundary being
     * defended is identical either way — a stray query in a service method is a statement that
     * escaped review.
     *
     * @param persistencePackages package patterns nothing outside {@code <base>..dao..} may touch
     */
    public static ArchRule persistenceStaysInTheDaoLayer(String basePackage, String... persistencePackages) {
        return noClasses()
                .that()
                .resideOutsideOfPackage(basePackage + "..dao..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(persistencePackages)
                .because("every statement the service issues must be readable in one place");
    }

    public static ArchRule noFieldInjection() {
        return noFields()
                .should()
                .beAnnotatedWith(Autowired.class)
                .because("constructor injection makes dependencies explicit, final, and testable without a container");
    }

    public static ArchRule noStandardStreams() {
        return GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
    }

    public static ArchRule noJavaUtilLogging() {
        return GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
    }

    public static ArchRule noLegacyDateApi() {
        return GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME.because(
                "java.time is the only date API this platform uses");
    }
}
