package org.quarkus.agent;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import dev.langchain4j.agent.tool.Tool;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit architecture governance verifying native GraalVM Mandrel compliance and CDI scoping.
 */
@AnalyzeClasses(packages = "org.quarkus.agent")
public class AgentArchitectureTest {

    @ArchTest
    public static final ArchRule toolsMustBeApplicationScoped =
        methods()
            .that().areAnnotatedWith(Tool.class)
            .should().beDeclaredInClassesThat().areAnnotatedWith(ApplicationScoped.class)
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule recordsAndEntitiesMustRegisterReflection =
        classes()
            .that().resideInAnyPackage("..model..", "..entity..", "..remote..", "..gui..")
            .and().areTopLevelClasses()
            .and().areNotInterfaces()
            .and().haveSimpleNameNotEndingWith("Test")
            .and().haveSimpleNameNotEndingWith("IT")
            .should().beAnnotatedWith(RegisterForReflection.class);

    @ArchTest
    public static final ArchRule entitiesMustNotBeAccessedOutsideTheirPackage =
        classes()
            .that().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().onlyBeAccessed().byClassesThat().resideInAPackage("org.quarkus.agent.entity..");

    @ArchTest
    public static final ArchRule testMethodsMustNotBeAnnotatedWithTransactional =
        methods()
            .that().areAnnotatedWith(org.junit.jupiter.api.Test.class)
            .or().areAnnotatedWith(org.junit.jupiter.api.BeforeEach.class)
            .or().areAnnotatedWith(org.junit.jupiter.api.AfterEach.class)
            .should().notBeAnnotatedWith(jakarta.transaction.Transactional.class);

    private static final com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass> CONTAIN_TEST_OR_LIFECYCLE_METHODS =
        new com.tngtech.archunit.base.DescribedPredicate<>("contain test or lifecycle methods or end with Test/IT") {
            @Override
            public boolean test(com.tngtech.archunit.core.domain.JavaClass javaClass) {
                return javaClass.getMethods().stream().anyMatch(m ->
                    m.isAnnotatedWith(org.junit.jupiter.api.Test.class)
                        || m.isAnnotatedWith(org.junit.jupiter.api.BeforeEach.class)
                        || m.isAnnotatedWith(org.junit.jupiter.api.AfterEach.class)
                ) || javaClass.getSimpleName().endsWith("Test") || javaClass.getSimpleName().endsWith("IT");
            }
        };

    @ArchTest
    public static final ArchRule testClassesMustNotBeAnnotatedWithTransactional =
        classes()
            .that(CONTAIN_TEST_OR_LIFECYCLE_METHODS)
            .should().notBeAnnotatedWith(jakarta.transaction.Transactional.class);

    @ArchTest
    public static final ArchRule testsMustNotUseReflectionToBypassEncapsulation =
        noClasses()
            .that().haveSimpleNameEndingWith("Test")
            .or().haveSimpleNameEndingWith("IT")
            .should().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                com.tngtech.archunit.core.domain.properties.HasName.Predicates.name("setAccessible")
            ))
            .as("Test classes must practice black-box behavioral verification and not use setAccessible(true)");
}
