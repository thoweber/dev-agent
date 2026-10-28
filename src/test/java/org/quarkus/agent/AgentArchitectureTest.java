package org.quarkus.agent;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import dev.langchain4j.agent.tool.Tool;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

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
}
