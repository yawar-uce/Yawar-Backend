package ec.yawar.donors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Hexagonal rules of YAW-DOC-07 §7.4, checked on every build. */
@AnalyzeClasses(packages = "ec.yawar.donors", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainHasNoFrameworkCode = noClasses().that().resideInAPackage("..donors.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                    "org.hibernate..", "ec.yawar.platform..")
            .as("the domain is plain Java: no Spring, JPA or platform imports");

    @ArchTest
    static final ArchRule domainDoesNotSeeOuterLayers = noClasses().that().resideInAPackage("..donors.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..donors.application..", "..donors.infrastructure..",
                    "..donors.interfaces..");

    @ArchTest
    static final ArchRule applicationDoesNotSeeAdapters = noClasses().that().resideInAPackage("..donors.application..")
            .should().dependOnClassesThat().resideInAnyPackage("..donors.infrastructure..", "..donors.interfaces..");

    @ArchTest
    static final ArchRule noRestClientsToOtherServices = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.web.client.RestTemplate")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.web.client.RestClient")
            .as("no REST between services (C-02): integrate through RabbitMQ");
}
