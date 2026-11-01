package guru.thomasweber.devagent;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import dev.langchain4j.agent.tool.Tool;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Entity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit architecture governance verifying native GraalVM Mandrel compliance and CDI scoping.
 */
@AnalyzeClasses(packages = "guru.thomasweber.devagent")
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
            .that().areAnnotatedWith(Entity.class)
            .should().onlyBeAccessed().byClassesThat().resideInAPackage("guru.thomasweber.devagent.entity..");

    @ArchTest
    public static final ArchRule testMethodsMustNotBeAnnotatedWithTransactional =
        methods()
            .that().areAnnotatedWith(Test.class)
            .or().areAnnotatedWith(BeforeEach.class)
            .or().areAnnotatedWith(AfterEach.class)
            .should().notBeAnnotatedWith(Transactional.class);

    private static final DescribedPredicate<JavaClass> CONTAIN_TEST_OR_LIFECYCLE_METHODS =
        new DescribedPredicate<>("contain test or lifecycle methods or end with Test/IT") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getMethods().stream().anyMatch(m ->
                    m.isAnnotatedWith(Test.class)
                        || m.isAnnotatedWith(BeforeEach.class)
                        || m.isAnnotatedWith(AfterEach.class)
                ) || javaClass.getSimpleName().endsWith("Test") || javaClass.getSimpleName().endsWith("IT");
            }
        };

    @ArchTest
    public static final ArchRule testClassesMustNotBeAnnotatedWithTransactional =
        classes()
            .that(CONTAIN_TEST_OR_LIFECYCLE_METHODS)
            .should().notBeAnnotatedWith(Transactional.class);

    @ArchTest
    public static final ArchRule testsMustNotUseReflectionToBypassEncapsulation =
        noClasses()
            .that().haveSimpleNameEndingWith("Test")
            .or().haveSimpleNameEndingWith("IT")
            .should().callMethodWhere(JavaCall.Predicates.target(
                HasName.Predicates.name("setAccessible")
            ))
            .as("Test classes must practice black-box behavioral verification and not use setAccessible(true)");
}
